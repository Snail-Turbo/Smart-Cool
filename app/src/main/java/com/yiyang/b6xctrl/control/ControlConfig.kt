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
    val baseWindLevel: Int = 4500,
    val baseColdLevel: Int = 178,
    val windMap: List<Pair<Float, Int>> = listOf(48f to 4800, 54f to 5400, 60f to 6000),
    val coldMap: List<Pair<Float, Int>> = listOf(48f to 190, 54f to 200, 60f to 205),
    val sampleIntervalMs: Long = 2000,
    val tempSource: TempSource = TempSource.CPU_GPU_MAX,
)
