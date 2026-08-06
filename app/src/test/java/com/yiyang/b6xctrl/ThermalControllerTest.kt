package com.yiyang.b6xctrl

import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.ThermalController
import com.yiyang.b6xctrl.control.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalControllerTest {

    private val config = ControlConfig() // 默认 42/36

    /** ByteArray 内容比较（Kotlin contains 用引用相等） */
    private fun List<ByteArray>.has(bytes: ByteArray): Boolean =
        any { it.contentEquals(bytes) }

    @Test
    fun coldTemp_returnsShutdownCommands() {
        val c = ThermalController(config)
        val cmds = c.onTemperature(30f)
        // B6X 无软件停机：停机 = 最低档 4500 + 冷 178（23 B2 2D 00）
        assertTrue(cmds.has(byteArrayOf(0x23, 0xB2.toByte(), 0x2D, 0x00)))
        assertEquals(ThermalState.OFF, c.state)
    }

    @Test
    fun hotTemp_startsWithMappedLevels() {
        val c = ThermalController(config)
        val cmds = c.onTemperature(50f)
        // 50°C ≥ 48 断点 → 风速 4800 制冷 190，风扇/制冷开
        assertTrue(cmds.has(byteArrayOf(0x23, 0xBE.toByte(), 0x30, 0x00))) // 23 BE 30 00 = 风速4800 冷190
        assertTrue(cmds.has(byteArrayOf(0x25, 0x00)))
        assertTrue(cmds.has(byteArrayOf(0x26, 0x00)))
        assertEquals(ThermalState.RUNNING, c.state)
    }

    @Test
    fun lowestBand_usesBaseWindLevel() {
        val c = ThermalController(config)
        val cmds = c.onTemperature(43f) // ≥42 启动，<48 用基础档 4500/178
        assertTrue(cmds.has(byteArrayOf(0x23, 0xB2.toByte(), 0x2D, 0x00))) // 23 B2 2D 00
        assertEquals(ThermalState.RUNNING, c.state)
    }

    @Test
    fun hysteresis_keepsRunningInBand() {
        val c = ThermalController(config)
        c.onTemperature(45f)
        val cmds = c.onTemperature(40f)
        assertTrue(cmds.isEmpty())
        assertEquals(ThermalState.RUNNING, c.state)
    }

    @Test
    fun belowStopTemp_shutsDown() {
        val c = ThermalController(config)
        c.onTemperature(45f)
        val cmds = c.onTemperature(35f)
        // B6X 无软件停机：降到最低档
        assertTrue(cmds.has(byteArrayOf(0x23, 0xB2.toByte(), 0x2D, 0x00)))
        assertEquals(ThermalState.OFF, c.state)
    }

    @Test
    fun alreadyOff_hotTemp_doesNotDuplicateShutdown() {
        val c = ThermalController(config)
        c.onTemperature(30f)
        val cmds = c.onTemperature(28f)
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun tempRisingAcrossBands_updatesLevels() {
        val c = ThermalController(config)
        c.onTemperature(50f)
        val cmds = c.onTemperature(62f)
        // 62°C ≥60 断点 → 风速6000(0x3C) 制冷205(0xCD) = 23 CD 3C 00
        assertTrue(cmds.has(byteArrayOf(0x23, 0xCD.toByte(), 0x3C, 0x00)))
    }
}
