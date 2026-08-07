package com.yiyang.b6xctrl

import com.yiyang.b6xctrl.protocol.WaspWingProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaspWingProtocolTest {

    @Test
    fun openReadNotification_isSingleByte13() {
        assertArrayEquals(byteArrayOf(0x13), WaspWingProtocol.openReadNotification())
    }

    @Test
    fun setWindLevel_buildsCommand() {
        assertArrayEquals(byteArrayOf(0x11, 0x78.toByte()), WaspWingProtocol.setWindLevel(120))
        assertArrayEquals(byteArrayOf(0x11, 0xFF.toByte()), WaspWingProtocol.setWindLevel(255))
    }

    @Test
    fun setColdLevel_buildsCommand() {
        assertArrayEquals(byteArrayOf(0x12, 0x8C.toByte()), WaspWingProtocol.setColdLevel(140))
    }

    @Test
    fun setFanOn_buildsCommand() {
        // 官方极性（offOnWind）：on=true → 25 00，on=false → 25 01
        assertArrayEquals(byteArrayOf(0x25, 0x00), WaspWingProtocol.setFanOn(true))
        assertArrayEquals(byteArrayOf(0x25, 0x01), WaspWingProtocol.setFanOn(false))
    }

    @Test
    fun setCoolOn_buildsCommand() {
        // 官方极性（offOnCold）：on=true → 26 00，on=false → 26 01
        assertArrayEquals(byteArrayOf(0x26, 0x00), WaspWingProtocol.setCoolOn(true))
        assertArrayEquals(byteArrayOf(0x26, 0x01), WaspWingProtocol.setCoolOn(false))
    }

    @Test
    fun manualModeCommand_buildsB6xCommand() {
        // B6X 手动 = 23 <冷档> <风速/100> <风速%100>，4 字节
        assertArrayEquals(
            byteArrayOf(0x23, 0xB2.toByte(), 0x2D, 0x00),
            WaspWingProtocol.manualModeCommand(4500, 178),
        )
        // 默认 D 档值：风速 6000、制冷 200
        assertArrayEquals(
            byteArrayOf(0x23, 0xC8.toByte(), 0x3C, 0x00),
            WaspWingProtocol.manualModeCommand(6000, 200),
        )
        // 230 是合法上限，原样透传；229 保留原值
        assertArrayEquals(
            byteArrayOf(0x23, 0xE6.toByte(), 0x3C, 0x00),
            WaspWingProtocol.manualModeCommand(6000, 230),
        )
        assertArrayEquals(
            byteArrayOf(0x23, 0xE5.toByte(), 0x3C, 0x00),
            WaspWingProtocol.manualModeCommand(6000, 229),
        )
        // 制冷 clamp：超上限 230 被压到 230（231、255）
        assertArrayEquals(
            byteArrayOf(0x23, 0xE6.toByte(), 0x3C, 0x00),
            WaspWingProtocol.manualModeCommand(6000, 231),
        )
        assertArrayEquals(
            byteArrayOf(0x23, 0xE6.toByte(), 0x3C, 0x00),
            WaspWingProtocol.manualModeCommand(6000, 255),
        )
        // 制冷 clamp：低于下限 1 被压到 1（0、负数）
        assertArrayEquals(
            byteArrayOf(0x23, 0x01, 0x3C, 0x00),
            WaspWingProtocol.manualModeCommand(6000, 0),
        )
        assertArrayEquals(
            byteArrayOf(0x23, 0x01, 0x3C, 0x00),
            WaspWingProtocol.manualModeCommand(6000, -5),
        )
    }

    @Test
    fun shutdownCommands_usesB6xManualLowest() {
        // B6X 无软件停机，停机 = 平衡档（制冷40 + 风速4600）
        val cmds = WaspWingProtocol.shutdownCommands()
        assertEquals(1, cmds.size)
        assertArrayEquals(byteArrayOf(0x23, 0x28, 0x2E, 0x00), cmds[0])
    }

    @Test
    fun setLightOn_buildsCommand() {
        assertArrayEquals(byteArrayOf(0x10, 0xFF.toByte()), WaspWingProtocol.setLightOn(true))
        assertArrayEquals(byteArrayOf(0x10, 0x00), WaspWingProtocol.setLightOn(false))
    }

    @Test
    fun parseStatus_parsesB6Layout() {
        // B6 布局（实测 B6X 实际使用）：[2]=冷面 [3]=小数 [4]=runMode [5]=超频
        // [6]*100+[18]=风速 [7]=制冷 [8]=灯 [9]=目标温 [11]*100+[10]=转速
        // [14]=风运行 [15]=冷运行 [16]=热面
        val data = byteArrayOf(
            0x13,            // [0] 命令
            0x00,            // [1] ipType
            0x18,            // [2] 冷面温度 +24
            0x05,            // [3] 小数 5
            0x01,            // [4] runMode=1 手动
            0xFF.toByte(),   // [5] 超频可用
            0x2D,            // [6] 风速高 = 45
            0x64,            // [7] 制冷档 100
            0xFF.toByte(),   // [8] 灯亮
            0x0C,            // [9] 目标温 12
            0x0F,            // [10] 转速低 15
            0x0A,            // [11] 转速高 10 → 10*100+15=1015（官方B6分支公式）
            0x32,            // [12] 超频风速高
            0x00,            // [13] 超频制冷
            0x64,            // [14] 风运行 100
            0x50,            // [15] 冷运行 80
            0x21,            // [16] 热面 33
            0x00,            // [17] 保护
            0x00,            // [18] 风速低 = 0 → 45*100+0=4500
            0x00,            // [19]
        )
        val s = WaspWingProtocol.parseStatus(data)!!
        assertEquals(1, s.runMode)
        assertEquals(100, s.coldLevel)
        assertEquals(4500, s.windLevel)
        assertEquals(12, s.targetTemp)
        assertEquals(24.05f, s.coldTempC, 0.001f)
        assertEquals(33f, s.hotTempC, 0.001f)
        assertEquals(1015, s.speedRpm)
        assertEquals(true, s.lightOn)
    }

    @Test
    fun parseStatus_userRealPackets_speedFormulaVerified() {
        // 用户实测（官方手动最低速）：13 00 05 03 02 FF 2D B2 00 0C BC 11 ...
        // 官方 B6 分支公式 setSpeed(value[11]*100+value[10]) = 17*100+188 = 1888
        val data = byteArrayOf(
            0x13, 0x00, 0x05, 0x03, 0x02, 0xFF.toByte(), 0x2D, 0xB2.toByte(),
            0x00, 0x0C, 0xBC.toByte(), 0x11, 0x32, 0xB2.toByte(), 0x94.toByte(),
            0xB2.toByte(), 0x29, 0x01, 0x00, 0x00,
        )
        val s = WaspWingProtocol.parseStatus(data)!!
        assertEquals(1888, s.speedRpm)
        assertEquals(5.03f, s.coldTempC, 0.001f)
        assertEquals(4500, s.windLevel)
        assertEquals(178, s.coldLevel)
        assertEquals(2, s.runMode) // B6X 手动模式 = 2
        assertEquals(false, s.lightOn) // [8]=00 灯灭
        assertEquals(41f, s.hotTempC, 0.001f) // [16]=0x29=41
    }

    @Test
    fun parseStatus_negativeColdTemp() {
        // 有符号 byte：0xF6 = -10，位于 [2]（B6 布局）
        val data = byteArrayOf(0x13, 0x00, 0xF6.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val s = WaspWingProtocol.parseStatus(data)!!
        assertEquals(-10f, s.coldTempC, 0.001f)
    }

    @Test
    fun parseStatus_non13_returnsNull() {
        assertNull(WaspWingProtocol.parseStatus(byteArrayOf(0x51, 0x00)))
    }
}
