package com.yiyang.b6xctrl.thermal

import java.io.BufferedReader
import java.io.InputStreamReader

object RootHelper {

    private var cached: Boolean? = null

    /** 探测 su 是否可用（结果缓存）。 */
    fun isRootAvailable(): Boolean {
        cached?.let { return it }
        val available = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            code == 0 && out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
        cached = available
        return available
    }

    /** 以 root 执行命令，返回 stdout；失败返回 null。 */
    fun runAsRoot(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
            if (p.waitFor() == 0) out else if (err.isBlank()) null else null
        } catch (_: Exception) {
            null
        }
    }
}
