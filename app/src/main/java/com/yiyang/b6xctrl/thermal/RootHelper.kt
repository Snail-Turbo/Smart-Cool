package com.yiyang.b6xctrl.thermal

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootHelper {

    /** su 探测/命令的等待上限：su 环境异常（授权弹窗、daemon 未就绪）时不无限阻塞 */
    private const val SU_TIMEOUT_SEC = 2L

    private var cached: Boolean? = null

    /** 探测 su 是否可用（结果缓存）。
     *  注意：可能被主线程调用（服务 onCreate 的 TempReader 构造），必须限时，
     *  否则 su 挂起会拖垮前台服务启动（5s 未 startForeground 被杀进程）。 */
    fun isRootAvailable(): Boolean {
        cached?.let { return it }
        val available = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            // 先限时等待退出，再读输出：避免无限阻塞（进程未退出时流无 EOF）
            if (p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                val out = p.inputStream.bufferedReader().readText()
                p.exitValue() == 0 && out.contains("uid=0")
            } else {
                p.destroy()
                false
            }
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
            // 限时等待，超时直接放弃（不无限挂起控制循环）
            if (!p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroy()
                null
            } else {
                p.inputStream.bufferedReader().readText()
            }
        } catch (_: Exception) {
            null
        }
    }
}
