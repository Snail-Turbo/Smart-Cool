package com.yiyang.b6xctrl.ble

import com.yiyang.b6xctrl.protocol.WaspWingProtocol

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class BleUiState(
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val deviceStatus: WaspWingProtocol.DeviceStatus? = null,
    /** 最近收到的 0x13 状态包 hex（调试用） */
    val lastRawHex: String? = null,
)
