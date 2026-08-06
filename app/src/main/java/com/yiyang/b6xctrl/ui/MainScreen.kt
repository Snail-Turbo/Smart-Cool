package com.yiyang.b6xctrl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

/** 运行模式名称（[4] 字节含义待标定：实测用户手动最低速时读到 0 和 2 两种值） */
private fun runModeName(mode: Int): String = when (mode) {
    0 -> "模式0(待标定)"
    1 -> "模式1"
    2 -> "模式2"
    3 -> "模式3"
    4 -> "模式4"
    else -> "未知($mode)"
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.uiState.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showExperiment by remember { mutableStateOf(false) }

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
                        "模式 ${runModeName(s.runMode)} | 冷面 ${fmtTemp(s.coldTempC)}°C | " +
                            "热面 ${fmtTemp(s.hotTempC)}°C | 转速 ${s.speedRpm} RPM",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "制冷档 ${s.coldLevel} | 目标温 ${s.targetTemp}°C | 灯 ${if (s.lightOn) "亮" else "灭"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ui.ble.lastRawHex?.let { hex ->
                        Text(
                            "状态包: $hex",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                // 命令调试（最近发送）
                if (ui.ble.lastSentCommands.isNotEmpty()) {
                    Text(
                        "最近命令: ${ui.ble.lastSentCommands.joinToString(" / ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (ui.ble.hasSendError) {
                    Text(
                        "⚠️ 有命令发送失败（未连接或写特征缺失）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // 系统温度卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "系统温度（参考源：${ui.config.tempSource.label}）",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    ui.systemTempC?.let { "%.1f °C".format(it) } ?: "--",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                // 设备真实运行状态（来自 0x13 状态包） vs 控制目标（来自温度判断）
                val deviceRunning = ui.ble.deviceStatus?.isRunning
                val targetRunning = ui.thermalState.name == "RUNNING"
                val runningText = when {
                    deviceRunning == null -> "未知"
                    deviceRunning -> "设备运行中"
                    else -> "设备已停"
                }
                val targetText = if (targetRunning) "目标:启动" else "目标:停机"
                Text(
                    "实际: $runningText | $targetText | " +
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
            // 手动控制（0-100%，映射设备档位 120~255）
            var windPercent by remember { mutableStateOf(50) }
            var coldPercent by remember { mutableStateOf(50) }
            val windLevel = 120 + ((255 - 120) * windPercent / 100)
            val coldLevel = 120 + ((255 - 120) * coldPercent / 100)
            ui.ble.deviceStatus?.let { s ->
                Text(
                    "当前模式：${runModeName(s.runMode)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text("风扇速度：$windPercent%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = windPercent.toFloat(),
                onValueChange = {
                    windPercent = it.toInt()
                    viewModel.setManualWindLevel(120 + ((255 - 120) * windPercent / 100))
                },
                valueRange = 0f..100f,
            )
            Text("制冷强度：$coldPercent%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = coldPercent.toFloat(),
                onValueChange = {
                    coldPercent = it.toInt()
                    viewModel.setManualColdLevel(120 + ((255 - 120) * coldPercent / 100))
                },
                valueRange = 0f..100f,
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
            OutlinedButton(onClick = { showExperiment = !showExperiment }, modifier = Modifier.weight(1f)) {
                Text(if (showExperiment) "收起实验" else "实验功能")
            }
        }

        if (showExperiment) {
            ExperimentPanel(viewModel = viewModel)
        }
    }
}

/** 实验面板：发送原始 hex 命令，校准 B6X 手动模式(0x22)等命令布局 */
@Composable
private fun ExperimentPanel(viewModel: MainViewModel) {
    var hexInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("实验功能（校准协议用）", style = MaterialTheme.typography.titleSmall)
            Text(
                "点按钮发送后，看上面\"状态包\"的 runMode（第3字节高4位）是否变化。" +
                    "把结果反馈给我即可校准正确命令。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = hexInput,
                onValueChange = { hexInput = it },
                label = { Text("自定义 hex（如 22 8C 8C）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val ok = viewModel.sendExperimentHex(hexInput)
                    feedback = if (ok) "已发送: $hexInput" else "hex 格式错误"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("发送自定义命令") }

            if (feedback != null) {
                Text(feedback.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }

            Text("快捷预设", style = MaterialTheme.typography.labelMedium)
            WaspWingProtocol.experimentPresets.forEach { (label, hex) ->
                OutlinedButton(
                    onClick = { viewModel.sendExperimentHex(hex) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
