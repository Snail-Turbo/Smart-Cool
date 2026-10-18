package com.yiyang.b6xctrl.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yiyang.b6xctrl.service.ControlService

/** 开机或应用更新后拉起温控前台服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                runCatching { ControlService.start(context.applicationContext) }
                    .onFailure { Log.w(TAG, "start ControlService failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "SmartCoolBoot"
    }
}
