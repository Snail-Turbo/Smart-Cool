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
        assertTrue(cmds.has(byteArrayOf(0x25, 0x00)))
        assertTrue(cmds.has(byteArrayOf(0x26, 0x00)))
        assertEquals(ThermalState.OFF, c.state)
    }

    @Test
    fun hotTemp_startsWithMappedLevels() {
        val c = ThermalController(config)
        val cmds = c.onTemperature(50f)
        assertTrue(cmds.has(byteArrayOf(0x11, 0xB4.toByte()))) // 180
        assertTrue(cmds.has(byteArrayOf(0x12, 0xAA.toByte()))) // 170
        assertTrue(cmds.has(byteArrayOf(0x25, 0x01)))
        assertTrue(cmds.has(byteArrayOf(0x26, 0x01)))
        assertEquals(ThermalState.RUNNING, c.state)
    }

    @Test
    fun lowestBand_usesBaseWindLevel() {
        val c = ThermalController(config)
        val cmds = c.onTemperature(43f)
        assertTrue(cmds.has(byteArrayOf(0x11, 0x8C.toByte()))) // 140
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
        assertTrue(cmds.has(byteArrayOf(0x25, 0x00)))
        assertTrue(cmds.has(byteArrayOf(0x26, 0x00)))
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
        assertTrue(cmds.has(byteArrayOf(0x11, 0xFF.toByte())))
        assertTrue(cmds.has(byteArrayOf(0x12, 0xFF.toByte())))
    }
}
