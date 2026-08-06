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

/** 运行模式名称（B6/B6X 官方映射：0=智能 2=手动 3=实验智能 4=自定义；1=旧版手动） */
private fun runModeName(mode: Int): String = when (mode) {
    0 -> "智能变频"
    1 -> "手动(旧)"
    2 -> "手动"
    3 -> "智能变频丨专家"
    4 -> "自定义"
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
                            "热面 ${fmtTemp(s.hotTempC)}°C",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // 分开显示两个速度相关字段，避免混淆
                    Text(
                        "风速档 ${s.windLevel}（范围4500~6000） | 转速值 ${s.speedRpm}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "制冷档 ${s.coldLevel} | 目标温 ${s.targetTemp}°C | 灯 ${if (s.lightOn) "亮" else "灭"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // 调试信息：状态包（默认隐藏，设置页可开启）
                    if (ui.config.showStatusPacket) {
                        ui.ble.lastRawHex?.let { hex ->
                            Text(
                                "状态包: $hex",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                // 调试信息：最近命令（默认隐藏，设置页可开启）
                if (ui.config.showRecentCommands && ui.ble.lastSentCommands.isNotEmpty()) {
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
                // 当前温控档位（来自 5 档曲线） + 设备真实运行状态
                val levelName = when (ui.currentLevelIndex) {
                    0 -> "A 待机"
                    1 -> "B 平衡"
                    2 -> "C"
                    3 -> "C'"
                    4 -> "D 满档"
                    else -> "档${ui.currentLevelIndex}"
                }
                val lvl = ui.config.levels.getOrNull(ui.currentLevelIndex)
                val levelDesc = if (lvl != null) "风${lvl.wind}/冷${lvl.cold}" else ""
                val deviceRunning = ui.ble.deviceStatus?.isRunning
                val runningText = when {
                    deviceRunning == null -> "未知"
                    deviceRunning -> "设备运行中"
                    else -> "设备已停"
                }
                Text(
                    "当前档：$levelName $levelDesc | 设备: $runningText | " +
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
            // 手动控制（0-100%，映射 B6X 风速 4000~6000 / 制冷 1~178）
            var windPercent by remember { mutableStateOf(50) }
            var coldPercent by remember { mutableStateOf(50) }
            val windLevel = 4000 + ((6000 - 4000) * windPercent / 100)
            val coldLevel = 1 + ((178 - 1) * coldPercent / 100)
            ui.ble.deviceStatus?.let { s ->
                Text(
                    "当前模式：${runModeName(s.runMode)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text("风扇速度：$windPercent%（4000~6000，勿低于4000否则停转）", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = windPercent.toFloat(),
                onValueChange = {
                    windPercent = it.toInt()
                    viewModel.setManualWindLevel(4000 + ((6000 - 4000) * windPercent / 100))
                },
                valueRange = 0f..100f,
            )
            Text("制冷强度：$coldPercent%（1~178）", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = coldPercent.toFloat(),
                onValueChange = {
                    coldPercent = it.toInt()
                    viewModel.setManualColdLevel(1 + ((178 - 1) * coldPercent / 100))
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

/** 实验面板：发送原始 hex 命令，校准 B6X 手动模式(0x23)等命令布局 */
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
                "怎么验证：点下方按钮发送后，看连接卡片里「模式」是否变成【手动】、" +
                    "「风速档」是否变化。反馈这些数字给我即可校准。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "说明：0x23 是 B6X 手动命令，4 字节：23 <制冷档> <风速档/100> <风速档%100>。" +
                    "风速档范围 4500~6000（4500=最低，6000=最高）。注意：上面的「转速值」和「风速档」是两个不同字段。" +
                    "25/26 开关命令对 B6X 可能无效（那是 B3/B5 系列的）。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = hexInput,
                onValueChange = { hexInput = it },
                label = { Text("自定义 hex（如 23 B2 2D 00）") },
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
