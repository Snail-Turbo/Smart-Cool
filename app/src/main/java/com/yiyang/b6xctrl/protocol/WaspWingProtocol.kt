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

    /** 风扇开关（⚠️ 官方极性：on=true → 25 00，on=false → 25 01，已按官方 offOnWind 修正） */
    fun setFanOn(on: Boolean): ByteArray = byteArrayOf(0x25, if (on) 0x00 else 0x01)

    /** 制冷(TEC)开关（⚠️ 官方极性：on=true → 26 00，on=false → 26 01） */
    fun setCoolOn(on: Boolean): ByteArray = byteArrayOf(0x26, if (on) 0x00 else 0x01)

    /** 灯开关 */
    fun setLightOn(on: Boolean): ByteArray = byteArrayOf(0x10, if (on) 0xFF.toByte() else 0x00)

    /** B6X 手动模式命令（0x23，4 字节，字节码级实证）：
     *  23 <制冷档(178/200)> <风速/100> <风速%100>
     *  风速 4500 → 23 B2 2D 00 */
    fun manualModeCommand(wind: Int, cold: Int = 178): ByteArray {
        val w = wind.coerceIn(4500, 5500)
        return byteArrayOf(0x23, cold.toByte(), (w / 100).toByte(), (w % 100).toByte())
    }

    /** 全关命令：切手动最低风速 + 关风扇 + 关制冷（B6X 正确路径） */
    fun shutdownCommands(): List<ByteArray> = listOf(
        manualModeCommand(4500, 178),
        setFanOn(false),
        setCoolOn(false),
    )

    // ================= 实验功能：B6X 手动命令 0x23（字节码级实证） =================
    // B6X 手动 = 23 <冷档> <风速/100> <风速%100>，4 字节；风速控制值 4500~5500

    /** 解析用户输入的 hex 字符串（"23 B2 2D 00" / "23b22d00"），非法返回 null */
    fun parseHexInput(s: String): ByteArray? {
        val cleaned = s.replace(" ", "").replace(",", "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val byte = cleaned.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            if (byte > 255) return null
            out[i] = byte.toByte()
        }
        return out
    }

    /** 实验预设：名称 → hex（B6X 手动 0x23 布局，风速 4500~5500；含 25/26 极性 A/B） */
    val experimentPresets: List<Pair<String, String>> = listOf(
        "手动 23 B2 2D 00(风速4500 冷178)" to "23 B2 2D 00",
        "手动 23 B2 32 00(风速5000 冷178)" to "23 B2 32 00",
        "手动 23 B2 37 00(风速5500 冷178)" to "23 B2 37 00",
        "手动 23 C8 2D 00(风速4500 冷200)" to "23 C8 2D 00",
        "智能变频 21 12(目标18°C)" to "21 12",
        // 25/26 极性 A/B（官方 on=true→00，App 已按官方修正；保留 A/B 实测）
        "风扇开? 25 00" to "25 00",
        "风扇关? 25 01" to "25 01",
        "制冷开? 26 00" to "26 00",
        "制冷关? 26 01" to "26 01",
        "灯开 10 FF" to "10 FF",
        "灯关 10 00" to "10 00",
    )

    /** 从 0x13 状态包解析设备状态；非 0x13 或长度不足返回 null */
    fun parseStatus(data: ByteArray): DeviceStatus? {
        if (data.isEmpty() || data[0].toInt() != 0x13) return null
        // ⚠️ 短包保护：B6 布局至少需要访问 [9]，不足 10 字节直接拒绝（防越界崩溃）
        if (data.size < 10) return null
        // 实测确认：B6X 固件实际使用 B6 布局（byte[2]=冷面温度 11~14°C 合理，
        // 而 B6X 布局把 byte[7]=0x7D 当温度会错读出 125°C）。
        // 解析 B6 布局，若结果异常则回退 B6X 布局。
        val b6 = parseB6Layout(data)
        if (b6.coldTempC in REASONABLE_MIN..REASONABLE_MAX) return b6
        val b6x = parseB6xLayout(data)
        return if (b6x.coldTempC in REASONABLE_MIN..REASONABLE_MAX) b6x else b6
    }

    private const val REASONABLE_MIN = -40f
    private const val REASONABLE_MAX = 120f

    /** B6 布局（实测确认是 B6X 实际使用的布局，来自逆向 B6 分支）：
     *  [1]=ipType [2]=冷面温度(有符号) [3]=小数 [4]=模式(0=智能 2=手动[B6X] 3=实验 4=自定义)
     *  [5]=超频可用(==255) [6]*100+[18]=风速控制值(4500=最低~5500=最高) [7]=制冷档
     *  [8]=灯(==255) [9]=目标温度 [10..11]=转速 = [11]*100+[10]（官方 B6 分支原文）
     *  [14]=风速运行档 [15]=制冷运行档 [16]=热面温度 [17]=保护位 */
    private fun parseB6Layout(data: ByteArray): DeviceStatus {
        val b = { i: Int -> data[i].toInt() and 0xFF }
        val coldTempC = data[2].toInt() + (b(3) / 100.0f)
        val runMode = b(4)
        val windLevel = if (data.size > 18) b(6) * 100 + b(18) else b(6) * 100
        val coldLevel = b(7)
        val lightOn = b(8) == 0xFF
        val targetTemp = b(9)
        // 官方 B6 分支原文：setSpeed((value[11] * 100) + (value[10] & 255))
        // 用户实测：13 00 05 03 02 FF 2D B2 00 0C BC 11 ... → 17*100+188 = 1888
        val speedRpm = if (data.size > 11) b(11) * 100 + b(10) else 0
        val windRunLevel = if (data.size > 14) b(14) else 0
        val coldRunLevel = if (data.size > 15) b(15) else 0
        val hotTempC = if (data.size > 16) data[16].toFloat() else Float.NaN
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
