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
        assertArrayEquals(byteArrayOf(0x25, 0x01), WaspWingProtocol.setFanOn(true))
        assertArrayEquals(byteArrayOf(0x25, 0x00), WaspWingProtocol.setFanOn(false))
    }

    @Test
    fun setCoolOn_buildsCommand() {
        assertArrayEquals(byteArrayOf(0x26, 0x01), WaspWingProtocol.setCoolOn(true))
        assertArrayEquals(byteArrayOf(0x26, 0x00), WaspWingProtocol.setCoolOn(false))
    }

    @Test
    fun setLightOn_buildsCommand() {
        assertArrayEquals(byteArrayOf(0x10, 0xFF.toByte()), WaspWingProtocol.setLightOn(true))
        assertArrayEquals(byteArrayOf(0x10, 0x00), WaspWingProtocol.setLightOn(false))
    }

    @Test
    fun parseStatus_parsesB6xLayout() {
        val data = byteArrayOf(
            0x13,            // [0] 命令
            0x00,            // [1] ipType
            0x10,            // [2] 高4位=1(手动) 低4位=0
            0x8C.toByte(),   // [3] coldLevel=140
            0x01, 0x2C,      // [4][5] windLevel=1*100+44=144
            0x12,            // [6] targetTemp=18
            0x18,            // [7] 冷面温度 +24
            0x05,            // [8] 小数 5
            0x28,            // [9] 热面温度 +40
            0x8C.toByte(),   // [10] coldRunLevel=140
            0x8C.toByte(),   // [11] windRunLevel=140
            0x0F, 0xA0.toByte(), // [12][13] speed=15*100+160=1660 RPM
            0xFF.toByte(),   // [14] lightState=255
            0x01,            // [15] ledType
            0x00,            // [16] quickSwitchRunMode
            0x00,            // [17] protection
        )
        val s = WaspWingProtocol.parseStatus(data)!!
        assertEquals(1, s.runMode)
        assertEquals(140, s.coldLevel)
        assertEquals(144, s.windLevel)
        assertEquals(18, s.targetTemp)
        assertEquals(24.05f, s.coldTempC, 0.001f)
        assertEquals(40f, s.hotTempC, 0.001f)
        assertEquals(1660, s.speedRpm)
        assertEquals(true, s.lightOn)
    }

    @Test
    fun parseStatus_negativeColdTemp() {
        // 有符号 byte：0xF6 = -10
        val data = byteArrayOf(0x13, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0xF6.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val s = WaspWingProtocol.parseStatus(data)!!
        assertEquals(-10f, s.coldTempC, 0.001f)
    }

    @Test
    fun parseStatus_non13_returnsNull() {
        assertNull(WaspWingProtocol.parseStatus(byteArrayOf(0x51, 0x00)))
    }
}
