package com.yiyang.b6xctrl.tile

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.yiyang.b6xctrl.MainActivity
import com.yiyang.b6xctrl.R
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.service.ControlService

/** 控制中心磁贴：单击切换自动重连开关，外观随开关状态变化（开=白/亮，关=灰/暗） */
class B6xTileService : TileService() {

    companion object {
        /** 当前正在展示的磁贴实例（onStartListening/onStopListening 维护） */
        @Volatile
        private var activeInstance: B6xTileService? = null

        /** 服务状态变化时刷新磁贴；无实例则跳过（下次展示时 onStartListening 会刷新） */
        fun refreshTiles() {
            activeInstance?.refreshState()
        }
    }

    override fun onStartListening() {
        activeInstance = this
        refreshState()
    }

    override fun onStopListening() {
        activeInstance = null
    }

    override fun onClick() {
        val svc = ControlService.instance
        if (svc != null) {
            // 服务在运行：单击切换自动重连
            svc.toggleAutoReconnect()
            refreshState()
            return
        }
        // 服务未运行：不能在此后台启动前台服务（Android 12+ 抛
        // ForegroundServiceStartNotAllowedException 杀进程）。改为打开 app——
        // MainViewModel 初始化即拉起温控服务（前台启动，合法）
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 按当前开关状态刷新磁贴外观 */
    private fun refreshState() {
        val tile = qsTile ?: return
        val on = ControlService.instance?.uiState?.value?.config?.autoReconnect
            ?: ControlConfig().autoReconnect
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "自动重连"
        tile.subtitle = if (on) "开" else "关"
        tile.contentDescription = "自动重连 ${if (on) "开" else "关"}"
        tile.icon = Icon.createWithResource(this, R.drawable.tile_icon).apply {
            setTint(if (on) Color.WHITE else Color.GRAY)
        }
        tile.updateTile()
    }
}
