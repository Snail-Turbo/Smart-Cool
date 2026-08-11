package com.yiyang.b6xctrl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yiyang.b6xctrl.R
import com.yiyang.b6xctrl.ble.BleConnectionState
import com.yiyang.b6xctrl.ble.BleManager
import com.yiyang.b6xctrl.ble.BleUiState
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempSource
import com.yiyang.b6xctrl.control.ThermalController
import com.yiyang.b6xctrl.control.ThermalState
import com.yiyang.b6xctrl.protocol.WaspWingProtocol
import com.yiyang.b6xctrl.thermal.TempReader
import com.yiyang.b6xctrl.thermal.TempSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val ble: BleUiState = BleUiState(),
    val systemTempC: Float? = null,
    val temps: TempSnapshot = TempSnapshot(),
    val thermalState: ThermalState = ThermalState.OFF,
    val currentLevelIndex: Int = 0,
    val autoMode: Boolean = true,
    val config: ControlConfig = ControlConfig(),
    val rootAvailable: Boolean = false,
)

class ControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bleManager: BleManager
    private lateinit var tempReader: TempReader
    private val repo = AppRepository(this)
    private val controller = ThermalController(ControlConfig())
    private var controlJob: Job? = null
    private var heartbeatJob: Job? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 供 UI 绑定的 Binder，暴露服务实例 */
    inner class LocalBinder : Binder() {
        val service: ControlService get() = this@ControlService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        tempReader = TempReader(this)
        _uiState.value = _uiState.value.copy(rootAvailable = tempReader.isRoot)
        createNotificationChannel()
        startForegroundCompat()
        // 聚合 BLE 状态
        scope.launch {
            bleManager.uiState.collect { ble ->
                _uiState.value = _uiState.value.copy(ble = ble)
                // 连接成功 → 记住设备 MAC 与型号名（下次免扫描直连 + MAC 直连时显示型号）
                if (ble.connectionState == BleConnectionState.CONNECTED) {
                    bleManager.connectedAddress?.let { addr ->
                        scope.launch { repo.saveDeviceAddress(addr) }
                    }
                    bleManager.connectedName?.let { name ->
                        if (name.isNotBlank()) {
                            scope.launch { repo.saveDeviceName(name) }
                        }
                    }
                }
            }
        }
        // 设备名解析成功（含直连后扫描补名）→ 持久化，供下次 MAC 直连显示型号
        bleManager.onNameResolved = { name ->
            if (name.isNotBlank()) {
                scope.launch { repo.saveDeviceName(name) }
            }
        }
        startControlLoop()
        startHeartbeatLoop()
        // 启动自动恢复连接：有记住的设备就按 MAC 直连（免扫描），并带上型号名显示
        scope.launch {
            val addr = repo.deviceAddressFlow.first()
            if (addr != null) {
                val name = repo.deviceNameFlow.first()
                bleManager.connectToDevice(addr, name)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        controlJob?.cancel()
        heartbeatJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startControlLoop() {
        controlJob?.cancel()
        controlJob = scope.launch {
            while (isActive) {
                val cfg = _uiState.value.config
                val snapshot = tempReader.readSnapshot()
                val temp = pickControlTemp(cfg, snapshot)
                _uiState.value = _uiState.value.copy(
                    systemTempC = temp,
                    temps = snapshot,
                )

                if (_uiState.value.autoMode &&
                    _uiState.value.ble.connectionState == BleConnectionState.CONNECTED
                ) {
                    temp?.let { t ->
                        val cmds = controller.onTemperature(t)
                        if (cmds.isNotEmpty()) bleManager.sendCommands(cmds)
                    }
                }
                _uiState.value = _uiState.value.copy(
                    thermalState = controller.state,
                    currentLevelIndex = controller.currentLevelIndex,
                )
                delay(cfg.sampleIntervalMs)
            }
        }
    }

    /** 独立心跳：每 5s 发一次 0x13 确保设备持续推送状态包（手动/自动模式都发，避免链路静默超时） */
    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_uiState.value.ble.connectionState == BleConnectionState.CONNECTED) {
                    bleManager.sendCommand(WaspWingProtocol.openReadNotification())
                }
            }
        }
    }

    fun connect() {
        bleManager.startScan()
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun setAutoMode(auto: Boolean) {
        _uiState.value = _uiState.value.copy(autoMode = auto)
        if (auto) {
            // 立即按当前温度决策一次
            _uiState.value.systemTempC?.let { t ->
                val cmds = controller.onTemperature(t)
                if (cmds.isNotEmpty()) bleManager.sendCommands(cmds)
            }
        }
    }

    fun setManualWindLevel(level: Int) {
        val cold = controller.currentColdLevel
        bleManager.sendCommands(controller.onManualLevels(level, cold))
    }

    fun setManualColdLevel(level: Int) {
        val wind = controller.currentWindLevel
        bleManager.sendCommands(controller.onManualLevels(wind, level))
    }

    fun setManualPower(on: Boolean) {
        bleManager.sendCommands(controller.onManualPower(on))
    }

    /** 实验功能：发送原始 hex 命令（如 "22 8C 8C"）。返回是否成功解析并发送。 */
    fun sendExperimentHex(hex: String): Boolean {
        val bytes = WaspWingProtocol.parseHexInput(hex) ?: return false
        bleManager.sendCommand(bytes)
        return true
    }

    fun setConfig(config: ControlConfig) {
        _uiState.value = _uiState.value.copy(config = config)
        controller.updateConfig(config)
    }

    /** 按配置的温控参考源从温度快照中选取控制温度 */
    private fun pickControlTemp(config: ControlConfig, s: TempSnapshot): Float? = when (config.tempSource) {
        TempSource.CPU -> s.cpuC
        TempSource.GPU -> s.gpuC
        TempSource.BATTERY -> s.batteryC
        TempSource.DDR -> s.ddrC
        TempSource.CAM -> s.camC
        TempSource.ALL_MAX -> listOfNotNull(s.cpuC, s.gpuC, s.ddrC, s.camC, s.batteryC).maxOrNull()
        TempSource.CPU_GPU_MAX -> listOfNotNull(s.cpuC, s.gpuC).maxOrNull()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "温控服务", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("B6X 温控运行中")
            .setContentText("正在按系统温度调度散热器")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "b6x_ctrl"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, ControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlService::class.java))
        }
    }
}
