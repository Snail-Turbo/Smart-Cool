package com.yiyang.b6xctrl.control

data class ControlConfig(
    val startTemp: Float = 42f,
    val stopTemp: Float = 36f,
    val baseWindLevel: Int = 140,
    val baseColdLevel: Int = 140,
    val windMap: List<Pair<Float, Int>> = listOf(48f to 180, 54f to 220, 60f to 255),
    val coldMap: List<Pair<Float, Int>> = listOf(48f to 170, 54f to 210, 60f to 255),
    val sampleIntervalMs: Long = 2000,
)
