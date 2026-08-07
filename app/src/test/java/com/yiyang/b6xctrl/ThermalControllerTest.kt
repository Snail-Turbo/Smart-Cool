package com.yiyang.b6xctrl

import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempLevel
import com.yiyang.b6xctrl.control.ThermalController
import com.yiyang.b6xctrl.control.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalControllerTest {

    private val config = ControlConfig() // 5 档默认：30/33/36/38/39

    /** ByteArray 内容比较（Kotlin contains 用引用相等） */
    private fun List<ByteArray>.has(bytes: ByteArray): Boolean =
        any { it.contentEquals(bytes) }

    /** A 档：<30°C → 制冷1 风4500 = 23 01 2D 00 */
    private val aCmd = byteArrayOf(0x23, 0x01, 0x2D, 0x00)
    /** B 档：≥33°C → 制冷40 风4600 = 23 28 2E 00 */
    private val bCmd = byteArrayOf(0x23, 0x28, 0x2E, 0x00)
    /** C 档：≥36°C → 制冷170 风5804 = 23 AA 3A 04 */
    private val cCmd = byteArrayOf(0x23, 0xAA.toByte(), 0x3A, 0x04)
    /** C' 档：≥38°C → 制冷180 风6000 = 23 B4 3C 00 */
    private val cPrimeCmd = byteArrayOf(0x23, 0xB4.toByte(), 0x3C, 0x00)
    /** D 档：≥39°C → 制冷200 风6000 = 23 C8 3C 00 */
    private val dCmd = byteArrayOf(0x23, 0xC8.toByte(), 0x3C, 0x00)

    @Test
    fun belowFirstThreshold_usesLevelA() {
        val c = ThermalController(config)
        val cmds = c.onTemperature(29f)
        assertTrue(cmds.has(aCmd))
        assertEquals(ThermalState.OFF, c.state)
        assertEquals(0, c.currentLevelIndex)
    }

    @Test
    fun levelB_at33() {
        val c = ThermalController(config)
        c.onTemperature(29f) // A
        val cmds = c.onTemperature(33f)
        assertTrue(cmds.has(bCmd))
        assertEquals(1, c.currentLevelIndex)
        assertEquals(ThermalState.RUNNING, c.state)
    }

    @Test
    fun levelC_at36() {
        val c = ThermalController(config)
        c.onTemperature(33f) // B
        val cmds = c.onTemperature(36f)
        assertTrue(cmds.has(cCmd))
        assertEquals(2, c.currentLevelIndex)
    }

    @Test
    fun levelCprime_at38() {
        val c = ThermalController(config)
        c.onTemperature(36f) // C
        val cmds = c.onTemperature(38f)
        assertTrue(cmds.has(cPrimeCmd))
        assertEquals(3, c.currentLevelIndex)
    }

    @Test
    fun levelD_at39() {
        val c = ThermalController(config)
        c.onTemperature(38f) // C'
        val cmds = c.onTemperature(39f)
        assertTrue(cmds.has(dCmd))
        assertEquals(4, c.currentLevelIndex)
        assertEquals(ThermalState.RUNNING, c.state)
    }

    @Test
    fun sameLevel_returnsEmpty() {
        val c = ThermalController(config)
        c.onTemperature(40f) // D
        val cmds = c.onTemperature(45f) // 仍在 D
        assertTrue(cmds.isEmpty())
        assertEquals(4, c.currentLevelIndex)
    }

    @Test
    fun coolingDown_stepsDown() {
        val c = ThermalController(config)
        c.onTemperature(40f) // D
        val cmds = c.onTemperature(31f) // 降回 A（<33）
        assertTrue(cmds.has(aCmd))
        assertEquals(0, c.currentLevelIndex)
        assertEquals(ThermalState.OFF, c.state)
    }

    @Test
    fun manualLevels_buildsCommand() {
        val c = ThermalController(config)
        val cmds = c.onManualLevels(5500, 100)
        assertTrue(cmds.has(byteArrayOf(0x23, 100, 0x37, 0x00)))
    }

    @Test
    fun updateConfig_usesNewLevels() {
        val c = ThermalController(config)
        val newCfg = ControlConfig(levels = listOf(TempLevel(25f, 10, 4500), TempLevel(35f, 50, 5000)))
        c.updateConfig(newCfg)
        // 30°C 达到 25°C 档但未达 35°C 档 → 第 0 档（制冷10 风4500）
        val cmds = c.onTemperature(30f)
        assertTrue(cmds.has(byteArrayOf(0x23, 0x0A, 0x2D, 0x00)))
        // 升到 40°C → 第 1 档（制冷50 风5000）
        val cmds2 = c.onTemperature(40f)
        assertTrue(cmds2.has(byteArrayOf(0x23, 0x32, 0x32, 0x00)))
    }
}
