package com.yiyang.b6xctrl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yiyang.b6xctrl.ble.BleConnectionState
import com.yiyang.b6xctrl.protocol.WaspWingProtocol

/** 温度格式化：null/NaN/越界显示 "--" */
private fun fmtTemp(v: Float?): String =
    if (v == null || v.isNaN() || v.isInfinite()) "--"
    else "%.1f".format(v)

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, viewModel = viewModel)
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 连接状态卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("连接状态", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (ui.ble.connectionState) {
                            BleConnectionState.DISCONNECTED -> "未连接"
                            BleConnectionState.SCANNING -> "扫描中…"
                            BleConnectionState.CONNECTING -> "连接中…"
                            BleConnectionState.CONNECTED -> "已连接 ${ui.ble.deviceName ?: ""}"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.ble.connectionState == BleConnectionState.DISCONNECTED) {
                        Button(onClick = { viewModel.connect() }) { Text("连接") }
                    } else {
                        OutlinedButton(onClick = { viewModel.disconnect() }) { Text("断开") }
                    }
                }
                ui.ble.deviceStatus?.let { s ->
                    Text(
                        "冷面 ${fmtTemp(s.coldTempC)}°C | 热面 ${fmtTemp(s.hotTempC)}°C | " +
                            "转速 ${s.speedRpm} RPM | 风速档 ${s.windLevel}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ui.ble.lastRawHex?.let { hex ->
                        Text(
                            "状态包: $hex",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // 系统温度卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("系统温度", style = MaterialTheme.typography.labelMedium)
                Text(
                    ui.systemTempC?.let { "%.1f °C".format(it) } ?: "--",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "温控状态：${if (ui.thermalState.name == "RUNNING") "运行中" else "已停机"} | " +
                        "root: ${if (ui.rootAvailable) "可用" else "未检测到"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 温度明细（学习 Scene 温度监视器：BAT/CPU/GPU/DDR/CAM）
                val t = ui.temps
                Text(
                    "BAT ${fmtTemp(t.batteryC)} | CPU ${fmtTemp(t.cpuC)} | GPU ${fmtTemp(t.gpuC)} | " +
                        "DDR ${fmtTemp(t.ddrC)} | CAM ${fmtTemp(t.camC)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 自动/手动切换
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动温控", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = ui.autoMode, onCheckedChange = { viewModel.setAutoMode(it) })
        }

        if (!ui.autoMode) {
            // 手动控制
            val currentWind = ui.ble.deviceStatus?.windLevel ?: ui.config.baseWindLevel
            Text(
                "风速档：$currentWind（${WaspWingProtocol.WIND_LEVEL_MIN}~${WaspWingProtocol.WIND_LEVEL_MAX}）",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = currentWind.coerceIn(
                    WaspWingProtocol.WIND_LEVEL_MIN,
                    WaspWingProtocol.WIND_LEVEL_MAX,
                ).toFloat(),
                onValueChange = { viewModel.setManualWindLevel(it.toInt()) },
                valueRange = WaspWingProtocol.WIND_LEVEL_MIN.toFloat()..WaspWingProtocol.WIND_LEVEL_MAX.toFloat(),
            )
            Row {
                Button(onClick = { viewModel.setManualPower(true) }, modifier = Modifier.weight(1f)) {
                    Text("开启")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { viewModel.setManualPower(false) }, modifier = Modifier.weight(1f)) {
                    Text("关闭")
                }
            }
        }

        Row {
            OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) {
                Text("温控设置")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.stopService() }, modifier = Modifier.weight(1f)) {
                Text("停止服务")
            }
        }
    }
}
