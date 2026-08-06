package com.yiyang.b6xctrl.control

/** 温控参考温度来源 */
enum class TempSource(val label: String) {
    CPU_GPU_MAX("CPU/GPU最高"),
    CPU("CPU"),
    GPU("GPU"),
    BATTERY("电池"),
    DDR("DDR"),
    CAM("CAM"),
    ALL_MAX("全部最高"),
}

data class ControlConfig(
    val startTemp: Float = 42f,
    val stopTemp: Float = 36f,
    val baseWindLevel: Int = 140,
    val baseColdLevel: Int = 140,
    val windMap: List<Pair<Float, Int>> = listOf(48f to 180, 54f to 220, 60f to 255),
    val coldMap: List<Pair<Float, Int>> = listOf(48f to 170, 54f to 210, 60f to 255),
    val sampleIntervalMs: Long = 2000,
    val tempSource: TempSource = TempSource.CPU_GPU_MAX,
)
