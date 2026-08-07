package com.yiyang.b6xctrl.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.Locale

/** 一次采样的各类温度快照（单位 °C） */
data class TempSnapshot(
    val cpuC: Float? = null,      // CPU 类热区最高温
    val gpuC: Float? = null,      // GPU（qmx）类热区最高温
    val ddrC: Float? = null,      // DDR 类热区最高温
    val camC: Float? = null,      // CAM 类热区最高温
    val batteryC: Float? = null,  // 电池温度（BatteryManager API）
    val controlC: Float? = null,  // 用于温控的温度（CPU/GPU 最高合理值）
)

/**
 * 读取系统温度。
 *
 * 学习 Scene 工具箱（com.omarea）的实现经验：
 * 1. 按 thermal_zone 的 type 名称匹配分类（cpu/gpu/ddr/cam），不取全部 zone 最大值
 * 2. 合理性范围过滤（避免 mmw/sdr 等异常 zone 的假高温触发散热器）
 * 3. 电池温度用 Android BatteryManager API（0.1°C，无需 root）
 * 4. 保留上次有效值防闪变
 */
class TempReader(context: Context) {

    private val thermalDir = File("/sys/class/thermal")
    private val appContext = context.applicationContext
    val isRoot: Boolean = RootHelper.isRootAvailable()

    // 合理温度范围（°C），毫°C 文件读出的值在此范围外视为异常
    private val TEMP_MIN_C = -40f
    private val TEMP_MAX_C = 110f
    // 电池温度合理范围（Scene 用 10~100）
    private val BATT_MIN_C = 10f
    private val BATT_MAX_C = 100f

    // 上次有效控制温度（防闪变）
    private var lastControlC: Float? = null

    /**
     * 采集所有分类温度。返回各类最高合理温度。
     * 全部失败时各字段为 null（controlC 保留上次值）。
     */
    fun readSnapshot(): TempSnapshot {
        val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return TempSnapshot(controlC = lastControlC)

        var cpuMax: Float? = null
        var gpuMax: Float? = null
        var ddrMax: Float? = null
        var camMax: Float? = null

        for (zone in zones) {
            val type = readZoneType(zone.absolutePath)?.lowercase(Locale.ROOT) ?: continue
            // ⚠️ 跳过阈值/跳闸类传感器（cpu-hw-trip-0 固定报 105°C 并非实时温度）
            if (type.contains("trip") || type.contains("crit") || type.contains("max") || type.contains("limit")) continue
            val temp = readZoneTempC(zone.absolutePath) ?: continue
            if (temp !in TEMP_MIN_C..TEMP_MAX_C) continue // 范围过滤，排除异常值

            when {
                type.contains("cpu") -> cpuMax = maxOf(cpuMax ?: temp, temp)
                type.contains("gpu") || type.contains("qmx") -> gpuMax = maxOf(gpuMax ?: temp, temp)
                type.contains("ddr") || type.contains("pop_mem") -> ddrMax = maxOf(ddrMax ?: temp, temp)
                type.contains("cam") || type.contains("camera") -> camMax = maxOf(camMax ?: temp, temp)
            }
        }

        // 温控温度 = CPU/GPU 最高（真实计算热源；mmw/sdr/nsphvx 不参与，避免假高温）
        val control = listOfNotNull(cpuMax, gpuMax).maxOrNull()
        if (control != null) lastControlC = control

        val battery = readBatteryTempC()

        return TempSnapshot(
            cpuC = cpuMax,
            gpuC = gpuMax,
            ddrC = ddrMax,
            camC = camMax,
            batteryC = battery,
            controlC = control ?: lastControlC,
        )
    }

    /** 兼容旧调用：返回用于温控的温度 */
    fun readMaxTempC(): Float? = readSnapshot().controlC

    /** 读取单个热区温度（毫°C → °C）。 */
    fun readZoneTempC(zonePath: String): Float? {
        val tempFile = File(zonePath, "temp")
        val raw = readFile(tempFile.absolutePath)?.trim() ?: return null
        val millis = raw.toLongOrNull() ?: return null
        return millis / 1000f
    }

    /** 读取 type 字段（如 "cpu-1-1-1"、"qmx"），供 UI 展示。 */
    fun readZoneType(zonePath: String): String? =
        readFile(File(zonePath, "type").absolutePath)?.trim()

    /** 电池温度（0.1°C → °C）。
     *  优先读实时 sysfs（/sys/class/power_supply/battery/temp 或 bms/temp，十分之一度，
     *  随充电/负载实时变化），失败再 fallback 到 sticky 广播 ACTION_BATTERY_CHANGED
     *  （系统按阈值低频推送，会滞后）。范围过滤。 */
    private fun readBatteryTempC(): Float? {
        // 1) 优先：实时 sysfs（多个候选路径）
        val sysfsPaths = listOf(
            "/sys/class/power_supply/battery/temp",
            "/sys/class/power_supply/bms/temp",
            "/sys/class/power_supply/main/temp",
        )
        for (path in sysfsPaths) {
            val raw = readFile(path)?.trim()
            val deci = raw?.toIntOrNull()
            if (deci != null && deci >= 0) {
                val c = deci / 10f
                if (c in BATT_MIN_C..BATT_MAX_C) return c
            }
        }
        // 2) fallback：sticky 广播（0.1°C）
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val deci = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (deci < 0) return null
        val c = deci / 10f
        return if (c in BATT_MIN_C..BATT_MAX_C) c else null
    }

    private fun readFile(path: String): String? {
        return try {
            File(path).readText()
        } catch (_: Exception) {
            // 普通读失败，尝试 root
            if (isRoot) RootHelper.runAsRoot("cat \"$path\"") else null
        }
    }
}
