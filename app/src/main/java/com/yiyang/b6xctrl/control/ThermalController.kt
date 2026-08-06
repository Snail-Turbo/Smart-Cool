package com.yiyang.b6xctrl.control

import com.yiyang.b6xctrl.protocol.WaspWingProtocol

enum class ThermalState { OFF, RUNNING }

class ThermalController(config: ControlConfig) {

    private var config: ControlConfig = config

    var state: ThermalState = ThermalState.OFF
        private set
    var currentWindLevel: Int = config.baseWindLevel
        private set
    var currentColdLevel: Int = config.baseColdLevel
        private set

    /** 初始/刚进入 OFF 时是否已确认下发关闭命令（保证设备真正停机） */
    private var shutdownPending = true

    /** 输入系统温度(°C)，返回需要下发的命令列表（可能为空）。 */
    fun onTemperature(tempC: Float): List<ByteArray> {
        return when (state) {
            ThermalState.OFF -> {
                if (tempC >= config.startTemp) {
                    state = ThermalState.RUNNING
                    shutdownPending = false
                    applyLevels(tempC)
                } else if (shutdownPending) {
                    shutdownPending = false
                    WaspWingProtocol.shutdownCommands()
                } else {
                    emptyList()
                }
            }
            ThermalState.RUNNING -> {
                if (tempC <= config.stopTemp) {
                    state = ThermalState.OFF
                    shutdownPending = false
                    WaspWingProtocol.shutdownCommands()
                } else {
                    applyLevelsIfChanged(tempC)
                }
            }
        }
    }

    /** 手动模式：直接设风速控制值(4500~5500)与制冷档（B6X 用 0x23 手动命令）。
     *  同时同步自动状态机，避免手动/自动脱钩。 */
    fun onManualLevels(windLevel: Int, coldLevel: Int): List<ByteArray> {
        currentWindLevel = windLevel.coerceIn(WIND_MIN, WIND_MAX)
        currentColdLevel = coldLevel.coerceIn(COLD_MIN, COLD_MAX)
        return listOf(
            WaspWingProtocol.manualModeCommand(currentWindLevel, currentColdLevel),
            WaspWingProtocol.setFanOn(true),
            WaspWingProtocol.setCoolOn(true),
        )
    }

    /** 手动模式：仅开关。 */
    fun onManualPower(on: Boolean): List<ByteArray> {
        if (on) {
            return listOf(
                WaspWingProtocol.manualModeCommand(currentWindLevel, currentColdLevel),
                WaspWingProtocol.setFanOn(true),
                WaspWingProtocol.setCoolOn(true),
            )
        }
        return WaspWingProtocol.shutdownCommands()
    }

    companion object {
        /** B6X 风速控制值范围（实测上限 6000） */
        const val WIND_MIN = 4500
        const val WIND_MAX = 6000
        /** B6X 制冷档范围（实测最高 229，推荐 205） */
        const val COLD_MIN = 178
        const val COLD_MAX = 229
        const val COLD_RECOMMENDED = 205
    }

    /** 热更新配置（保留当前状态）。 */
    fun updateConfig(newConfig: ControlConfig) {
        config = newConfig
    }

    private fun applyLevels(tempC: Float): List<ByteArray> {
        currentWindLevel = mapLevel(tempC, config.windMap, config.baseWindLevel)
        currentColdLevel = mapLevel(tempC, config.coldMap, config.baseColdLevel)
        return listOf(
            WaspWingProtocol.manualModeCommand(currentWindLevel, currentColdLevel),
            WaspWingProtocol.setFanOn(true),
            WaspWingProtocol.setCoolOn(true),
        )
    }

    private fun applyLevelsIfChanged(tempC: Float): List<ByteArray> {
        val wind = mapLevel(tempC, config.windMap, config.baseWindLevel)
        val cold = mapLevel(tempC, config.coldMap, config.baseColdLevel)
        if (wind == currentWindLevel && cold == currentColdLevel) return emptyList()
        currentWindLevel = wind
        currentColdLevel = cold
        return listOf(
            WaspWingProtocol.manualModeCommand(wind, cold),
        )
    }

    private fun mapLevel(tempC: Float, map: List<Pair<Float, Int>>, base: Int): Int {
        var level = base
        for ((threshold, value) in map) {
            if (tempC >= threshold) level = value
        }
        return level
    }
}
