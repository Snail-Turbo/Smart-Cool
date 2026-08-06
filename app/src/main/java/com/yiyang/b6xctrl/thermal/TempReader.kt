package com.yiyang.b6xctrl.thermal

import android.content.Context
import java.io.File

class TempReader(context: Context) {

    private val thermalDir = File("/sys/class/thermal")
    val isRoot: Boolean = RootHelper.isRootAvailable()

    /**
     * 读取所有热区的最高温度（°C）。
     * 优先直接读（world-readable）；对读取失败的热区，若 root 可用则尝试 su。
     * 返回 null 表示全部读取失败。
     */
    fun readMaxTempC(): Float? {
        val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return null
        var max: Float? = null
        for (zone in zones) {
            val t = readZoneTempC(zone.absolutePath) ?: continue
            if (max == null || t > max) max = t
        }
        return max
    }

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

    private fun readFile(path: String): String? {
        return try {
            File(path).readText()
        } catch (_: Exception) {
            // 普通读失败，尝试 root
            if (isRoot) RootHelper.runAsRoot("cat \"$path\"") else null
        }
    }
}
