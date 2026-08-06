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

    /** 全关命令：档位降到最低 + 关风扇 + 关制冷（即使某个命令被忽略，功率也最小化） */
    fun shutdownCommands(): List<ByteArray> = listOf(
        setWindLevel(WIND_LEVEL_MIN),
        setColdLevel(WIND_LEVEL_MIN),
        setFanOn(false),
        setCoolOn(false),
    )

    /** 从 0x13 状态包解析设备状态；非 0x13 包返回 null */
    fun parseStatus(data: ByteArray): DeviceStatus? {
        if (data.isEmpty() || data[0].toInt() != 0x13) return null
        // B6X 与 B6 布局偏移不同：优先选择解析结果温度合理的那一个
        val b6x = parseB6xLayout(data)
        val b6 = parseB6Layout(data)
        val b6xOk = b6x.coldTempC in REASONABLE_MIN..REASONABLE_MAX
        val b6Ok = b6.coldTempC in REASONABLE_MIN..REASONABLE_MAX
        return when {
            b6xOk && !b6Ok -> b6x
            !b6xOk && b6Ok -> b6
            else -> b6x // 默认 B6X 布局
        }
    }

    private const val REASONABLE_MIN = -40f
    private const val REASONABLE_MAX = 120f

    /** B6X 布局（逆向文档 §5.1）：byte[7]=冷面、byte[9]=热面、byte[12]*100+byte[13]=转速 */
    private fun parseB6xLayout(data: ByteArray): DeviceStatus {
        val b = { i: Int -> data[i].toInt() and 0xFF }
        val runMode = (b(2) shr 4) and 0x0F
        val coldLevel = b(3)
        val windLevel = b(4) * 100 + b(5)
        val targetTemp = b(6)
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

    /** B6 布局（逆向 WaspWingDataInteractionController B6 分支）：
     *  byte[2]=冷面(有符号)、byte[4]=runMode、byte[6]=风速(单字节)、byte[7]=制冷、
     *  byte[9]=目标温度、byte[10..11]=转速(大端16位) */
    private fun parseB6Layout(data: ByteArray): DeviceStatus {
        val b = { i: Int -> data[i].toInt() and 0xFF }
        val coldTempC = data[2].toInt() + (b(3) / 100.0f)
        val runMode = b(4)
        val windLevel = b(6)
        val coldLevel = b(7)
        val lightOn = b(8) == 0xFF
        val targetTemp = b(9)
        val speedRpm = if (data.size > 11) (b(11) shl 8) + b(10) else 0
        return DeviceStatus(
            runMode = runMode,
            coldLevel = coldLevel,
            windLevel = windLevel,
            targetTemp = targetTemp,
            coldTempC = coldTempC,
            hotTempC = Float.NaN, // B6 布局无热面温度
            coldRunLevel = if (data.size > 15) b(15) else 0,
            windRunLevel = if (data.size > 14) b(14) else 0,
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
    ) {
        /** 设备真实运行状态：转速 >0 或任一运行档 >0 即视为运行中 */
        val isRunning: Boolean
            get() = speedRpm > 0 || windRunLevel > 0 || coldRunLevel > 0
    }
}
