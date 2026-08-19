package com.yiyang.b6xctrl.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yiyang.b6xctrl.protocol.WaspWingProtocol
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 连接超时：CONNECTING 持续超过该时长仍未成功则清理并重试（防一直卡\"连接中\"） */
private const val CONNECT_TIMEOUT_MS = 5_000L
/** 自动重连指数退避：起始延迟 */
private const val RECONNECT_BASE_DELAY_MS = 3_000L
/** 自动重连指数退避：最大延迟（封顶） */
private const val RECONNECT_MAX_DELAY_MS = 30_000L
/** 蓝牙重新开启后，等协议栈就绪再重连的延迟 */
private const val BT_ON_RECONNECT_DELAY_MS = 1_000L
/** 直连后补名扫描的最大时长（拿广播名用） */
private const val NAME_FETCH_SCAN_TIMEOUT_MS = 5_000L
class BleManager(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 监听蓝牙开关：重新开启时立即重连（不等退避定时），解决"断蓝牙再开要干等超时" */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    onBluetoothEnabled()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // ---- 自动重连 / 记住设备 ----
    /** 最近一次连接的设备（用于断连后自动重连） */
    private var lastDevice: BluetoothDevice? = null
    /** 最近一次成功连接（onServicesDiscovered 成功）的设备 MAC */
    private var lastConnectedAddress: String? = null
    /** 最近一次拿到的非空设备名（MAC 直连时系统读不到名字，用它显示） */
    private var knownName: String? = null
    /** 设备名解析成功回调（供上层持久化；直连后扫描补名也会触发） */
    var onNameResolved: ((String) -> Unit)? = null
    /** 指数退避尝试次数（成功连接后复位为 0） */
    private var reconnectAttempts = 0
    /** 用户主动断开标志：主动断开不自动重连 */
    private var userInitiatedDisconnect = false
    /** 自动重连开关：关闭后断开/蓝牙重开/启动恢复都不自动连接（手动连接不受影响） */
    private var autoReconnectEnabled = true
    private val autoReconnectRunnable = object : Runnable {
        override fun run() {
            if (!autoReconnectEnabled || userInitiatedDisconnect) return
            val dev = lastDevice ?: return
            connect(dev)
        }
    }

    /** 连接超时保护：10s 内未成功则清理并重试，避免一直卡在"连接中" */
    private val connectTimeoutRunnable = object : Runnable {
        override fun run() {
            if (_uiState.value.connectionState == BleConnectionState.CONNECTING) {
                handleDisconnected()
            }
        }
    }

    /** 上次成功连接的设备 MAC（供上层持久化，用于下次免扫描直连） */
    val connectedAddress: String? get() = lastConnectedAddress

    /** 最近一次拿到的非空设备名（供上层持久化，MAC 直连时显示用） */
    val connectedName: String? get() = knownName

    private var autoReconnecting: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                _uiState.value = _uiState.value.copy(autoReconnecting = value)
            }
        }

    // 命令队列：串行发送，间隔 35ms
    private val commandQueue = LinkedBlockingQueue<ByteArray>()
    private var lastSendTime = 0L
    private val commandWorker = Thread { commandLoop() }.apply { isDaemon = true; start() }

    // 目标设备名前缀（飞智散热器系列）
    private val deviceNamePrefixes = listOf("Flydigi")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (deviceNamePrefixes.any { name.startsWith(it) }) {
                _uiState.value = _uiState.value.copy(
                    deviceName = name,
                    connectionState = BleConnectionState.CONNECTING,
                )
                stopScan()
                connect(result.device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_CONNECTED) {
                // 底层返回已连接但 status 异常 → 实际未成功，清理并重试
                handleDisconnected()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                handleDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // 服务发现失败：清理并重试，避免 UI 一直卡在"连接中"
                handleDisconnected()
                return
            }
            val service = gatt.getService(UUID.fromString(WaspWingProtocol.SERVICE_UUID)) ?: return
            val readChar = service.getCharacteristic(UUID.fromString(WaspWingProtocol.READ_CHAR_UUID)) ?: return
            writeCharacteristic = service.getCharacteristic(UUID.fromString(WaspWingProtocol.WRITE_CHAR_UUID))

            // 使能通知
            gatt.setCharacteristicNotification(readChar, true)
            val cccd = readChar.getDescriptor(UUID.fromString(WaspWingProtocol.CCCD_UUID))
            cccd?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            // 连接成功：记住 MAC（供持久化/下次免扫描直连）、复位自动重连
            lastConnectedAddress = gatt.device.address
            reconnectAttempts = 0
            autoReconnecting = false
            mainHandler.removeCallbacks(autoReconnectRunnable)
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            _uiState.value = _uiState.value.copy(reconnectDelayMs = null)

            // 刷新设备名：优先用系统读到的远端名，读不到用缓存名，都没有才兜底 MAC
            val remoteName = gatt.device.name
            if (!remoteName.isNullOrBlank()) {
                knownName = remoteName
                onNameResolved?.invoke(remoteName)
            } else {
                // MAC 直连时系统可能没缓存广播名 → 主动扫描一次补名（拿广播里的 Flydigi B6X）
                fetchNameViaScan(gatt.device.address)
            }
            _uiState.value = _uiState.value.copy(
                connectionState = BleConnectionState.CONNECTED,
                deviceName = knownName ?: _uiState.value.deviceName,
            )
            // 请求状态推送
            sendCommand(WaspWingProtocol.openReadNotification())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            WaspWingProtocol.parseStatus(value)?.let { status ->
                _uiState.value = _uiState.value.copy(
                    deviceStatus = status,
                    lastRawHex = value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                )
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // 通知使能完成
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        // 用户主动发起连接：取消自动重连、复位重连状态
        userInitiatedDisconnect = false
        autoReconnecting = false
        mainHandler.removeCallbacks(autoReconnectRunnable)
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        reconnectAttempts = 0
        _uiState.value = _uiState.value.copy(reconnectDelayMs = null)
        val filters = listOf(ScanFilter.Builder().build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
        _uiState.value = _uiState.value.copy(connectionState = BleConnectionState.SCANNING)
    }

    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    /** 直连拿不到设备名时，主动扫描一次（按 MAC 过滤）补上广播名，最多 5s */
    private fun fetchNameViaScan(address: String) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val nameScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address.equals(address, ignoreCase = true)) {
                    val n = result.device.name
                    if (!n.isNullOrBlank()) {
                        knownName = n
                        onNameResolved?.invoke(n)
                        _uiState.value = _uiState.value.copy(deviceName = n)
                        adapter.bluetoothLeScanner?.stopScan(this)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                adapter.bluetoothLeScanner?.stopScan(this)
            }
        }
        adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            nameScanCallback,
        )
        // 兜底停止：最多扫 5s，拿到名字即提前停止
        mainHandler.postDelayed(
            { adapter.bluetoothLeScanner?.stopScan(nameScanCallback) },
            NAME_FETCH_SCAN_TIMEOUT_MS,
        )
    }

    fun connect(device: BluetoothDevice) {
        // 先清理旧连接：同设备重复 connectGatt 而旧 gatt 未 close，会丢回调卡在"连接中"
        closeGatt()
        lastDevice = device
        // 蓝牙未开启：不进入"连接中"干等超时，直接安排退避重试；蓝牙重开时接收器会立即重连
        if (bluetoothAdapter?.isEnabled != true) {
            handleDisconnected()
            return
        }
        userInitiatedDisconnect = false
        mainHandler.removeCallbacks(autoReconnectRunnable)
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        _uiState.value = _uiState.value.copy(
            connectionState = BleConnectionState.CONNECTING,
            deviceName = device.name ?: knownName ?: device.address,
        )
        // 连接超时保护：5s 未成功则清理并走重试（正常 BLE 连接 1~3s 内完成）
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    /** 按 MAC 免扫描直连（用于启动自动恢复连接 / 断连后重连记住的设备）
     *  @param displayName 持久化的设备名；MAC 直连时系统读不到名字，用它显示 */
    fun connectToDevice(address: String, displayName: String? = null) {
        if (!displayName.isNullOrBlank()) {
            knownName = displayName
        }
        val adapter = bluetoothAdapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Exception) {
            return
        }
        connect(device)
    }

    fun disconnect() {
        // 用户主动断开：不自动重连
        userInitiatedDisconnect = true
        autoReconnecting = false
        mainHandler.removeCallbacks(autoReconnectRunnable)
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        reconnectAttempts = 0
        _uiState.value = _uiState.value.copy(reconnectDelayMs = null)
        gatt?.disconnect()
    }

    fun sendCommands(cmds: List<ByteArray>) {
        cmds.forEach { commandQueue.offer(it) }
    }

    fun sendCommand(bytes: ByteArray) {
        commandQueue.offer(bytes)
    }

    private fun commandLoop() {
        while (true) {
            val cmd = try {
                commandQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            // 保证间隔 ≥35ms
            val wait = lastSendTime + 35 - System.currentTimeMillis()
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    break
                }
            }
            lastSendTime = System.currentTimeMillis()
            mainHandler.post {
                val g = gatt
                val char = writeCharacteristic
                val hex = cmd.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                if (g != null && char != null) {
                    var ok = writeNoResponse(g, char, cmd)
                    // NO_RESPONSE 高频写偶发返回 false（上一条未处理完），重试一次即可。
                    // 注意：不可在主线程 sleep（ANR），直接重发。
                    if (!ok) {
                        ok = writeNoResponse(g, char, cmd)
                    }
                    // hasSendError 表示「最近一次」发送状态：成功即清除，不粘滞。
                    _uiState.value = _uiState.value.copy(
                        lastSentCommands = (_uiState.value.lastSentCommands + hex).takeLast(6),
                        hasSendError = !ok,
                    )
                } else {
                    // 未连接或写特征缺失：记录发送失败
                    _uiState.value = _uiState.value.copy(
                        lastSentCommands = (_uiState.value.lastSentCommands + "$hex ⚠️未发送").takeLast(6),
                        hasSendError = true,
                    )
                }
            }
        }
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        writeCharacteristic = null
    }

    /** 自动重连开关（设置页/磁贴可切换）。关闭时取消挂起的退避重连，避免关掉后仍自动连上。 */
    fun setAutoReconnect(enabled: Boolean) {
        if (autoReconnectEnabled == enabled) return
        autoReconnectEnabled = enabled
        if (!enabled) {
            autoReconnecting = false
            mainHandler.removeCallbacks(autoReconnectRunnable)
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            reconnectAttempts = 0
            _uiState.value = _uiState.value.copy(reconnectDelayMs = null)
        }
    }

    /** 蓝牙重新开启：取消挂起的退避重连，稍等协议栈就绪后立即重连上次设备 */
    private fun onBluetoothEnabled() {
        if (!autoReconnectEnabled || userInitiatedDisconnect || lastDevice == null) return
        mainHandler.removeCallbacks(autoReconnectRunnable)
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        reconnectAttempts = 0
        autoReconnecting = true
        _uiState.value = _uiState.value.copy(reconnectDelayMs = BT_ON_RECONNECT_DELAY_MS)
        mainHandler.postDelayed(autoReconnectRunnable, BT_ON_RECONNECT_DELAY_MS)
    }

    /** 断连/失败统一处理：清理连接、复位状态、按指数退避安排自动重连 */
    private fun handleDisconnected() {
        closeGatt()
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        _uiState.value = _uiState.value.copy(
            connectionState = BleConnectionState.DISCONNECTED,
            deviceName = _uiState.value.deviceName,
        )
        if (!userInitiatedDisconnect && lastDevice != null && autoReconnectEnabled) {
            reconnectAttempts++
            // 指数退避：3s→6s→12s→24s→30s 封顶（用命名常量，避免 magic number）
            val delayMs = min(
                RECONNECT_MAX_DELAY_MS,
                RECONNECT_BASE_DELAY_MS * (1L shl min(reconnectAttempts - 1, 3)),
            )
            autoReconnecting = true
            _uiState.value = _uiState.value.copy(reconnectDelayMs = delayMs)
            mainHandler.postDelayed(autoReconnectRunnable, delayMs)
        } else {
            autoReconnecting = false
            _uiState.value = _uiState.value.copy(reconnectDelayMs = null)
        }
        userInitiatedDisconnect = false
    }

    /** 以 NO_RESPONSE 写模式写一条命令；返回系统是否接受本次写入（主线程调用，不 sleep）。 */
    private fun writeNoResponse(g: BluetoothGatt, char: BluetoothGattCharacteristic, cmd: ByteArray): Boolean {
        return try {
            char.value = cmd
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(char)
        } catch (_: Exception) {
            false
        }
    }
}
