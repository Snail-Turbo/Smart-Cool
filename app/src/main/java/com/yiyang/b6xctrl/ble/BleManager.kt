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
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yiyang.b6xctrl.protocol.WaspWingProtocol
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleManager(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

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
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                closeGatt()
                _uiState.value = BleUiState(
                    connectionState = BleConnectionState.DISCONNECTED,
                    deviceName = _uiState.value.deviceName,
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
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

            _uiState.value = _uiState.value.copy(connectionState = BleConnectionState.CONNECTED)
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

    fun connect(device: BluetoothDevice) {
        _uiState.value = _uiState.value.copy(
            connectionState = BleConnectionState.CONNECTING,
            deviceName = device.name ?: device.address,
        )
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    fun disconnect() {
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
                    char.value = cmd
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val ok = try {
                        g.writeCharacteristic(char)
                    } catch (_: Exception) {
                        false
                    }
                    _uiState.value = _uiState.value.copy(
                        lastSentCommands = (_uiState.value.lastSentCommands + hex).takeLast(6),
                        hasSendError = _uiState.value.hasSendError || !ok,
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
}
