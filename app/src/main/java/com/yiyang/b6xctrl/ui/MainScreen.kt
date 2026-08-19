package com.yiyang.b6xctrl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yiyang.b6xctrl.ble.BleConnectionState
import com.yiyang.b6xctrl.protocol.WaspWingProtocol
import kotlinx.coroutines.delay

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
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 连接状态卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("连接状态", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 自动重连倒计时（秒），随 reconnectDelayMs 变化重启
                    var reconnectCountdown by remember(ui.ble.reconnectDelayMs) {
                        mutableStateOf((ui.ble.reconnectDelayMs ?: 0L) / 1000L)
                    }
                    LaunchedEffect(ui.ble.reconnectDelayMs) {
                        var remaining = (ui.ble.reconnectDelayMs ?: 0L) / 1000L
                        while (remaining > 0) {
                            reconnectCountdown = remaining
                            delay(1000L)
                            remaining--
                        }
                        reconnectCountdown = 0L
                    }
                    Text(
                        when {
                            ui.ble.connectionState == BleConnectionState.DISCONNECTED &&
                                ui.ble.autoReconnecting -> "已断连，${reconnectCountdown}s 后自动重连…"
                            ui.ble.connectionState == BleConnectionState.DISCONNECTED -> "未连接"
                            ui.ble.connectionState == BleConnectionState.SCANNING -> "扫描中…"
                            ui.ble.connectionState == BleConnectionState.CONNECTING -> "连接中…"
                            else -> "已连接 ${ui.ble.deviceName ?: ""}"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.ble.connectionState == BleConnectionState.DISCONNECTED &&
                        !ui.ble.autoReconnecting
                    ) {
                        Button(onClick = { viewModel.connect() }) { Text("连接") }
                    } else if (ui.ble.connectionState == BleConnectionState.DISCONNECTED) {
                        OutlinedButton(onClick = { viewModel.connect() }) { Text("立即重试") }
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
                        "风速档 ${s.windLevel}（范围4500~7500） | 转速值 ${s.speedRpm}",
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
            // 手动控制（0-100%，映射 B6X 风速 4000~7500 / 制冷 1~230）
            var windPercent by remember { mutableStateOf(50) }
            var coldPercent by remember { mutableStateOf(50) }
            val windLevel = 4000 + ((7500 - 4000) * windPercent / 100)
            val coldLevel = 1 + ((230 - 1) * coldPercent / 100)
            ui.ble.deviceStatus?.let { s ->
                Text(
                    "当前模式：${runModeName(s.runMode)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text("风扇速度：$windPercent%（4000~7500，勿低于4000否则停转）", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = windPercent.toFloat(),
                onValueChange = {
                    windPercent = it.toInt()
                    viewModel.setManualWindLevel(4000 + ((7500 - 4000) * windPercent / 100))
                },
                valueRange = 0f..100f,
            )
            Text("制冷强度：$coldPercent%（1~230）", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = coldPercent.toFloat(),
                onValueChange = {
                    coldPercent = it.toInt()
                    viewModel.setManualColdLevel(1 + ((230 - 1) * coldPercent / 100))
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

        // 温控曲线图（自适应：平板并排，手机垂直）
        TempCurveSection(
            levels = ui.config.levels,
            currentTempC = ui.systemTempC,
            currentLevelIndex = ui.currentLevelIndex,
        )

        if (showExperiment) {
            ExperimentPanel(viewModel = viewModel)
        }
    }
}

/** 温控曲线区：平板（宽）并排放两张曲线，手机（窄）垂直叠放 */
@Composable
private fun TempCurveSection(
    levels: List<com.yiyang.b6xctrl.control.TempLevel>,
    currentTempC: Float?,
    currentLevelIndex: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("温控曲线", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "温度→风速 / 温度→制冷（当前档位标红）",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 600.dp) {
                    // 平板：并排
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            LevelCurveChart(
                                levels = levels,
                                valueSelector = { it.wind },
                                valueMin = 4000f,
                                valueMax = 7500f,
                                valueLabel = { "${it / 100}" },
                                title = "风速档（4500~7500）",
                                color = Color(0xFF1B5E20),
                                currentTempC = currentTempC,
                                currentLevelIndex = currentLevelIndex,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            LevelCurveChart(
                                levels = levels,
                                valueSelector = { it.cold },
                                valueMin = 0f,
                                valueMax = 230f,
                                valueLabel = { "$it" },
                                title = "制冷档（1~230）",
                                color = Color(0xFF00838F),
                                currentTempC = currentTempC,
                                currentLevelIndex = currentLevelIndex,
                            )
                        }
                    }
                } else {
                    // 手机：垂直
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LevelCurveChart(
                            levels = levels,
                            valueSelector = { it.wind },
                            valueMin = 4000f,
                            valueMax = 7500f,
                            valueLabel = { "${it / 100}" },
                            title = "风速档（4500~7500）",
                            color = Color(0xFF1B5E20),
                            currentTempC = currentTempC,
                            currentLevelIndex = currentLevelIndex,
                        )
                        LevelCurveChart(
                            levels = levels,
                            valueSelector = { it.cold },
                            valueMin = 0f,
                            valueMax = 230f,
                            valueLabel = { "$it" },
                            title = "制冷档（1~230）",
                            color = Color(0xFF00838F),
                            currentTempC = currentTempC,
                            currentLevelIndex = currentLevelIndex,
                        )
                    }
                }
            }
        }
    }
}

/** 单张阶梯曲线（温度→风速或制冷），当前温度画红点/红线 */
@Composable
private fun LevelCurveChart(
    levels: List<com.yiyang.b6xctrl.control.TempLevel>,
    valueSelector: (com.yiyang.b6xctrl.control.TempLevel) -> Int,
    valueMin: Float,
    valueMax: Float,
    valueLabel: (Int) -> String,
    title: String,
    color: Color,
    currentTempC: Float?,
    currentLevelIndex: Int,
) {
    // X 轴温度范围：20~70°C（含所有阈值）
    val tempMin = 20f
    val tempMax = 70f
    // Y 轴范围
    val yMin = valueMin
    val yMax = valueMax

    // 收集阶梯折线点（含起始水平线）
    val points = remember(levels) {
        buildList {
            // 起始（<第一档阈值）：第一档的值
            add(Offset(tempMin, levels.first().let(valueSelector).toFloat()))
            for (lvl in levels) {
                // 垂直跳变（从上一档值到本档值）
                add(Offset(lvl.thresholdC, lvl.let(valueSelector).toFloat()))
            }
            // 终点（>最后档阈值）：最后档的值
            add(Offset(tempMax, levels.last().let(valueSelector).toFloat()))
        }
    }

    Canvas(
        modifier = Modifier.fillMaxWidth().height(160.dp),
    ) {
        // 坐标映射
        fun x(t: Float) = (t - tempMin) / (tempMax - tempMin) * size.width
        fun y(v: Float) = size.height - (v - yMin) / (yMax - yMin) * size.height

        // 网格线
        for (i in 0..5) {
            val gy = size.height * i / 5f
            drawLine(
                color = Color.Gray.copy(alpha = 0.2f),
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1f,
            )
        }

        // 阶梯曲线
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(p.x), y(p.y))
            else path.lineTo(x(p.x), y(p.y))
        }
        drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // 当前温度竖线 + 交点红点
        currentTempC?.let { t ->
            if (t in tempMin..tempMax) {
                val cx = x(t)
                drawLine(
                    color = Color.Red.copy(alpha = 0.6f),
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 1.5f,
                )
                // 当前档位的值
                val curValue = if (currentLevelIndex in levels.indices) {
                    levels[currentLevelIndex].let(valueSelector).toFloat()
                } else levels.first().let(valueSelector).toFloat()
                drawCircle(
                    color = Color.Red,
                    radius = 6f,
                    center = Offset(cx, y(curValue)),
                )
            }
        }
    }

    // 图例 + 各档标注
    Text(title, style = MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        levels.forEachIndexed { i, lvl ->
            val isCurrent = i == currentLevelIndex
            Text(
                "${levelName2(i)}:${lvl.thresholdC.toInt()}°→${valueLabel(valueSelector(lvl))}",
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) Color.Red else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

private fun levelName2(i: Int): String = when (i) {
    0 -> "A"
    1 -> "B"
    2 -> "C"
    3 -> "C'"
    else -> "D"
}

/** 实验面板：发送原始 hex 命令，校准 B6X 手动模式(0x23)等命令布局。
 *  注意：本面板内容由外层主 Column 的 verticalScroll 统一滚动，
 *  这里不要再套 verticalScroll（同方向嵌套滚动会触发 IllegalStateException 闪退）。 */
@Composable
private fun ExperimentPanel(viewModel: MainViewModel) {
    var hexInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("实验功能（校准协议用）", style = MaterialTheme.typography.titleSmall)
            Text(
                "说明：0x23 是 B6X 手动命令，4 字节：23 <制冷档> <风速档/100> <风速档%100>。" +
                    "风速档范围 4500~7500（4500=最低，7500=最高）。注意：上面的「转速值」和「风速档」是两个不同字段。" +
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
