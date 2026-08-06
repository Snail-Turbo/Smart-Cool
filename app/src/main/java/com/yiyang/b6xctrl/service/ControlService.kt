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
import com.yiyang.b6xctrl.control.ThermalController
import com.yiyang.b6xctrl.control.ThermalState
import com.yiyang.b6xctrl.protocol.WaspWingProtocol
import com.yiyang.b6xctrl.thermal.TempReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val ble: BleUiState = BleUiState(),
    val systemTempC: Float? = null,
    val thermalState: ThermalState = ThermalState.OFF,
    val autoMode: Boolean = true,
    val config: ControlConfig = ControlConfig(),
    val rootAvailable: Boolean = false,
)

class ControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bleManager: BleManager
    private lateinit var tempReader: TempReader
    private val controller = ThermalController(ControlConfig())
    private var controlJob: Job? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val binder = object : Binder() {
        val service: ControlService get() = this@ControlService
    }

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
            }
        }
        startControlLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        controlJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startControlLoop() {
        controlJob?.cancel()
        controlJob = scope.launch {
            while (isActive) {
                val cfg = _uiState.value.config
                val temp = tempReader.readMaxTempC()
                _uiState.value = _uiState.value.copy(systemTempC = temp)

                if (_uiState.value.autoMode &&
                    _uiState.value.ble.connectionState == BleConnectionState.CONNECTED
                ) {
                    temp?.let { t ->
                        val cmds = controller.onTemperature(t)
                        if (cmds.isNotEmpty()) bleManager.sendCommands(cmds)
                    }
                    // 心跳：维持状态推送
                    bleManager.sendCommand(WaspWingProtocol.openReadNotification())
                }
                _uiState.value = _uiState.value.copy(thermalState = controller.state)
                delay(cfg.sampleIntervalMs)
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

    fun setConfig(config: ControlConfig) {
        _uiState.value = _uiState.value.copy(config = config)
        controller.updateConfig(config)
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
