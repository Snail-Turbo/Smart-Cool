package com.yiyang.b6xctrl.control

import com.yiyang.b6xctrl.protocol.WaspWingProtocol

enum class ThermalState { OFF, RUNNING }

/** 5 档温控曲线控制器（PC 风扇式）：
 *  温度达到某档阈值即切到该档，档位变化才下发命令（避免重复刷）。 */
class ThermalController(config: ControlConfig) {

    private var config: ControlConfig = config

    /** 当前所处档位索引（0=A 待机档） */
    var currentLevelIndex: Int = 0
        private set
    var currentWindLevel: Int = config.levels.first().wind
        private set
    var currentColdLevel: Int = config.levels.first().cold
        private set

    /** 是否已执行过首次下发（保证即使首帧就在 0 档，也会发一次命令同步设备状态） */
    private var initialized = false

    val state: ThermalState
        get() = if (currentLevelIndex == 0) ThermalState.OFF else ThermalState.RUNNING

    /** 输入系统温度(°C)，返回需要下发的命令列表（档位变化或首次时才有内容）。 */
    fun onTemperature(tempC: Float): List<ByteArray> {
        val idx = pickLevelIndex(tempC)
        if (!initialized) {
            initialized = true
        } else if (idx == currentLevelIndex) {
            return emptyList()
        }
        currentLevelIndex = idx
        val lvl = config.levels[idx]
        currentWindLevel = lvl.wind
        currentColdLevel = lvl.cold
        return listOf(WaspWingProtocol.manualModeCommand(lvl.wind, lvl.cold))
    }

    /** 手动模式：直接设风速控制值(4000~7500)与制冷档（B6X 用 0x23 手动命令）。 */
    fun onManualLevels(windLevel: Int, coldLevel: Int): List<ByteArray> {
        currentWindLevel = windLevel.coerceIn(WIND_MIN, WIND_MAX)
        currentColdLevel = coldLevel.coerceIn(COLD_MIN, COLD_MAX)
        return listOf(
            WaspWingProtocol.manualModeCommand(currentWindLevel, currentColdLevel),
        )
    }

    /** 手动模式：仅开关。关闭 = 最低档（A 档待机）。 */
    fun onManualPower(on: Boolean): List<ByteArray> {
        if (on) {
            return listOf(
                WaspWingProtocol.manualModeCommand(currentWindLevel, currentColdLevel),
            )
        }
        val a = config.levels.first()
        currentLevelIndex = 0
        currentWindLevel = a.wind
        currentColdLevel = a.cold
        return listOf(WaspWingProtocol.manualModeCommand(a.wind, a.cold))
    }

    companion object {
        /** B6X 风速控制值范围（用户自定：最低4000避免停转，最高7500） */
        const val WIND_MIN = 4000
        const val WIND_MAX = 7500
        /** B6X 制冷档范围（设备能力到 229，可调上限 230） */
        const val COLD_MIN = 1
        const val COLD_MAX = 230
        const val COLD_RECOMMENDED = 178
    }

    /** 热更新配置（保留当前状态）。 */
    fun updateConfig(newConfig: ControlConfig) {
        config = newConfig
    }

    /** 选择温度所属档位：最后一个阈值 ≤ 温度的档；低于第一档阈值 → 第 0 档 */
    private fun pickLevelIndex(tempC: Float): Int {
        var idx = 0
        config.levels.forEachIndexed { i, lvl ->
            if (tempC >= lvl.thresholdC) idx = i
        }
        return idx
    }
}
