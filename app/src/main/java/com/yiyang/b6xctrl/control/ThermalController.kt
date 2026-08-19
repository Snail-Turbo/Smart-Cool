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
    /** 手动控制过：下次自动决策必须无条件重发（设备实际状态与曲线状态可能不一致） */
    private var manualIntervened = false

    val state: ThermalState
        get() = if (currentLevelIndex == 0) ThermalState.OFF else ThermalState.RUNNING

    /** 输入系统温度(°C)，返回需要下发的命令列表（档位变化或首次时才有内容）。 */
    fun onTemperature(tempC: Float): List<ByteArray> {
        val idx = pickLevelIndex(tempC)
        if (!initialized || manualIntervened) {
            // 首次决策，或手动干预过：无条件重发（手动控制改变了设备实际状态，
            // 即使温度同档也必须切回曲线配置，否则设备停在手动档位）
            initialized = true
            manualIntervened = false
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
        manualIntervened = true
        currentWindLevel = windLevel.coerceIn(WIND_MIN, WIND_MAX)
        currentColdLevel = coldLevel.coerceIn(COLD_MIN, COLD_MAX)
        return listOf(
            WaspWingProtocol.manualModeCommand(currentWindLevel, currentColdLevel),
        )
    }

    /** 手动模式：仅开关。关闭 = 最低档（A 档待机）。 */
    fun onManualPower(on: Boolean): List<ByteArray> {
        manualIntervened = true
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

    /** 热更新配置。重置"已初始化"标记：即使温度仍在同一档，下一次决策也按新配置重发命令，
     *  否则 currentLevelIndex 未变会跳过下发，改风速/制冷/阈值不能实时生效。 */
    fun updateConfig(newConfig: ControlConfig) {
        config = newConfig
        initialized = false
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
