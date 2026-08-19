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

/** 一个温控档位：温度达到 thresholdC（含）时切换到该档 */
data class TempLevel(
    val thresholdC: Float, // 触发该档的温度阈值（°C）
    val cold: Int,         // 制冷档（0x23 第二字节）
    val wind: Int,         // 风速控制值（0x23 第三四字节，4000~7500）
)

/** 5 档温控曲线（PC 风扇式）：A<B<C<C'<D，温度达到阈值升档 */
data class ControlConfig(
    val levels: List<TempLevel> = listOf(
        TempLevel(30f, 1, 4500),   // A：<30°C 待机档
        TempLevel(33f, 40, 4600),  // B：≥33°C 平衡档
        TempLevel(36f, 170, 5804), // C：≥36°C 中档
        TempLevel(38f, 180, 6000), // C'：≥38°C 中高档（C 与 D 之间）
        TempLevel(39f, 200, 6000), // D：≥39°C 满档
    ),
    val sampleIntervalMs: Long = 2000,
    val tempSource: TempSource = TempSource.BATTERY,
    // 自动重连开关：断开后按指数退避自动恢复连接（用户手动断开不受影响）
    val autoReconnect: Boolean = true,
    // 界面调试开关（默认关闭，可在设置页开启）
    val showStatusPacket: Boolean = false,
    val showRecentCommands: Boolean = false,
)
