package com.yiyang.b6xctrl.ble

import com.yiyang.b6xctrl.protocol.WaspWingProtocol

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class BleUiState(
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val deviceStatus: WaspWingProtocol.DeviceStatus? = null,
    /** 最近收到的 0x13 状态包 hex（调试用） */
    val lastRawHex: String? = null,
    /** 最近发送的命令 hex（调试用，最多保留 6 条） */
    val lastSentCommands: List<String> = emptyList(),
    /** 是否有命令因未连接/写特征缺失而发送失败 */
    val hasSendError: Boolean = false,
)
