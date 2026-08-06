package com.yiyang.b6xctrl.protocol

object WaspWingProtocol {

    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val WRITE_CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val READ_CHAR_UUID = "0000ffe4-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val WIND_LEVEL_MIN = 120
    const val WIND_LEVEL_MAX = 255

    /** 请求设备状态推送 */
    fun openReadNotification(): ByteArray = byteArrayOf(0x13)

    /** 设置风速档位，level ∈ [120, 255]，越大风越大 */
    fun setWindLevel(level: Int): ByteArray =
        byteArrayOf(0x11, level.coerceIn(WIND_LEVEL_MIN, WIND_LEVEL_MAX).toByte())

    /** 设置制冷档位，level ∈ [120, 255] */
    fun setColdLevel(level: Int): ByteArray =
        byteArrayOf(0x12, level.coerceIn(WIND_LEVEL_MIN, WIND_LEVEL_MAX).toByte())

    /** 风扇开关 */
    fun setFanOn(on: Boolean): ByteArray = byteArrayOf(0x25, if (on) 0x01 else 0x00)

    /** 制冷(TEC)开关 */
    fun setCoolOn(on: Boolean): ByteArray = byteArrayOf(0x26, if (on) 0x01 else 0x00)

    /** 灯开关 */
    fun setLightOn(on: Boolean): ByteArray = byteArrayOf(0x10, if (on) 0xFF.toByte() else 0x00)

    /** 从 0x13 状态包解析设备状态；非 0x13 包返回 null */
    fun parseStatus(data: ByteArray): DeviceStatus? {
        if (data.isEmpty() || data[0].toInt() != 0x13) return null
        val b = { i: Int -> data[i].toInt() and 0xFF }
        val runMode = (b(2) shr 4) and 0x0F
        val coldLevel = b(3)
        val windLevel = b(4) * 100 + b(5)
        val targetTemp = b(6)
        // B6X 布局：byte[7]=冷面温度(有符号byte)，byte[8]=小数，byte[9]=热面温度(有符号byte)
        val coldTempC = data[7].toInt() + (b(8) / 100.0f)
        val hotTempC = data[9].toFloat()
        val coldRunLevel = b(10)
        val windRunLevel = b(11)
        val speedRpm = b(12) * 100 + b(13)
        val lightOn = b(14) == 0xFF
        return DeviceStatus(
            runMode = runMode,
            coldLevel = coldLevel,
            windLevel = windLevel,
            targetTemp = targetTemp,
            coldTempC = coldTempC,
            hotTempC = hotTempC,
            coldRunLevel = coldRunLevel,
            windRunLevel = windRunLevel,
            speedRpm = speedRpm,
            lightOn = lightOn,
        )
    }

    data class DeviceStatus(
        val runMode: Int,
        val coldLevel: Int,
        val windLevel: Int,
        val targetTemp: Int,
        val coldTempC: Float,
        val hotTempC: Float,
        val coldRunLevel: Int,
        val windRunLevel: Int,
        val speedRpm: Int,
        val lightOn: Boolean,
    )
}
