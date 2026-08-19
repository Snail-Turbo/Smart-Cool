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
import android.util.Log
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
import com.yiyang.b6xctrl.tile.B6xTileService
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
        instance = this
        // 前台服务必须先 startForeground（startForegroundService 后 5s 内未调即抛
        // ForegroundServiceDidNotStartInTimeException 杀进程）。任何可能阻塞的初始化
        // （su 探测等）都放后面，避免超时崩溃。
        createNotificationChannel()
        startForegroundCompat()
        bleManager = BleManager(this)
        tempReader = TempReader(this)
        _uiState.value = _uiState.value.copy(rootAvailable = tempReader.isRoot)
        // 聚合 BLE 状态
        scope.launch {
            bleManager.uiState.collect { ble ->
                _uiState.value = _uiState.value.copy(ble = ble)
                updateNotification()
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
        // 启动自动恢复连接：重连开关开启且有记住的设备才按 MAC 直连（免扫描），并带上型号名显示
        scope.launch {
            val cfg = repo.configFlow.first()
            bleManager.setAutoReconnect(cfg.autoReconnect)
            val addr = repo.deviceAddressFlow.first()
            if (cfg.autoReconnect && addr != null) {
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
        if (instance === this) instance = null
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
                updateNotification()
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
        updateNotification()
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

    /** 磁贴/快捷开关切换自动重连：持久化配置并同步 BleManager */
    fun toggleAutoReconnect() {
        val next = _uiState.value.config.copy(autoReconnect = !_uiState.value.config.autoReconnect)
        setConfig(next)
        scope.launch { repo.saveConfig(next) }
        B6xTileService.refreshTiles()
    }

    fun setConfig(config: ControlConfig) {
        val wasAutoReconnect = _uiState.value.config.autoReconnect
        _uiState.value = _uiState.value.copy(config = config)
        bleManager.setAutoReconnect(config.autoReconnect)
        controller.updateConfig(config)
        // 配置变更（保存/导入）实时生效：立即按当前温度重发一次挡位命令。
        // 即使温度未跨档，也把新风速/制冷推给设备；若尚未采到温度，控制循环下一轮兜底重发
        _uiState.value.systemTempC?.let { t ->
            val cmds = controller.onTemperature(t)
            if (cmds.isNotEmpty()) bleManager.sendCommands(cmds)
        }
        updateNotification()
        // 重连开关从关→开且未连接：立即尝试连接（不等断连事件）。
        // 用记住的 MAC 直连；从未成功连过（无 MAC）则等用户手动扫描连接
        if (config.autoReconnect && !wasAutoReconnect &&
            _uiState.value.ble.connectionState != BleConnectionState.CONNECTED
        ) {
            scope.launch {
                val addr = repo.deviceAddressFlow.first()
                if (addr != null) {
                    bleManager.connectToDevice(addr, repo.deviceNameFlow.first())
                }
            }
        }
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 系统限制等极端情况：前台服务启动失败不应拖垮整个进程
            Log.w(TAG, "startForeground 失败", e)
        }
    }

    /** 通知内容键：内容变化（状态/挡位/温度 ≥0.5°C）才重发，避免 2s 轮询频繁闪通知 */
    private var lastNotifiedKey: String? = null

    private fun levelName(i: Int): String = when (i) {
        0 -> "A挡"
        1 -> "B挡"
        2 -> "C挡"
        3 -> "C'挡"
        4 -> "D挡"
        else -> "挡$i"
    }

    private fun fmtTemp(v: Float?): String =
        if (v == null || v.isNaN() || v.isInfinite()) "--" else "%.1f".format(v)

    private fun buildNotification(): Notification {
        val s = _uiState.value
        val ble = s.ble
        val connected = ble.connectionState == BleConnectionState.CONNECTED
        // 通知最多两行（第三行在通知栏会被折叠）：
        // 已连接 = 挡位/风速(设备实际档)/制冷/连接/自动重连开关 + 冷热面/参考温度
        // 参考温度显示用户温控设置选定的源（默认电池，与主界面一致）
        val status = if (connected) {
            val lvl = s.config.levels.getOrNull(s.currentLevelIndex)
            val ds = ble.deviceStatus
            val wind = ds?.windLevel ?: lvl?.wind
            val cold = ds?.coldLevel ?: lvl?.cold
            "${levelName(s.currentLevelIndex)} · 风速$wind · 制冷$cold · 已连接 · " +
                "自动重连${if (s.config.autoReconnect) "开" else "关"}\n" +
                "冷面 ${fmtTemp(ds?.coldTempC)}°C · 热面 ${fmtTemp(ds?.hotTempC)}°C · " +
                "参考(${s.config.tempSource.label}) ${fmtTemp(s.systemTempC)}°C"
        } else if (ble.autoReconnecting) {
            "重连中 ${(ble.reconnectDelayMs ?: 0L) / 1000L}s\n" +
                "参考(${s.config.tempSource.label}) ${fmtTemp(s.systemTempC)}°C"
        } else {
            "未连接 · 自动重连${if (s.config.autoReconnect) "开" else "关"}\n" +
                "参考(${s.config.tempSource.label}) ${fmtTemp(s.systemTempC)}°C"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("B6X 温控运行中")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    /** 刷新常驻通知（内容无变化则不动作，见 lastNotifiedKey） */
    private fun updateNotification() {
        val s = _uiState.value
        val ble = s.ble
        val key = listOf(
            ble.connectionState.name,
            ble.autoReconnecting,
            s.config.autoReconnect,
            s.config.tempSource.name,
            s.autoMode,
            s.currentLevelIndex,
            ble.deviceStatus?.windLevel,
            ble.deviceStatus?.coldLevel,
            ble.deviceStatus?.coldTempC?.let { (it / 0.5f).toInt() },
            ble.deviceStatus?.hotTempC?.let { (it / 0.5f).toInt() },
            s.systemTempC?.let { (it / 0.5f).toInt() },
        ).joinToString("|")
        if (key == lastNotifiedKey) return
        lastNotifiedKey = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "ControlService"

        /** 供磁贴等外部组件访问的服务实例（onCreate/onDestroy 维护） */
        @Volatile
        var instance: ControlService? = null
            private set

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
