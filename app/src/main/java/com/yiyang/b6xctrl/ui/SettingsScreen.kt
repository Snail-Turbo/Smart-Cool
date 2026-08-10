package com.yiyang.b6xctrl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempLevel
import com.yiyang.b6xctrl.control.TempSource
import kotlin.math.roundToInt

/** 档位名称（A/B/C/C'/D） */
private fun levelName(i: Int): String = when (i) {
    0 -> "A"
    1 -> "B"
    2 -> "C"
    3 -> "C'"
    else -> "D"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val cfg = ui.config

    var interval by remember { mutableFloatStateOf(cfg.sampleIntervalMs.toFloat()) }
    var tempSource by remember { mutableStateOf(cfg.tempSource) }
    var sourceExpanded by remember { mutableStateOf(false) }
    var showStatusPacket by remember { mutableStateOf(cfg.showStatusPacket) }
    var showRecentCommands by remember { mutableStateOf(cfg.showRecentCommands) }

    // 出厂默认档位（恢复默认用）
    val defaultLevels = remember { ControlConfig().levels }

    // 每档可编辑：阈值/制冷/风速（局部状态，保存时才提交）
    val levelStates = remember(cfg) {
        cfg.levels.map { lvl ->
            LevelEditState(
                threshold = mutableFloatStateOf(lvl.thresholdC),
                cold = mutableFloatStateOf(lvl.cold.toFloat()),
                wind = mutableFloatStateOf(lvl.wind.toFloat()),
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("温控设置", style = MaterialTheme.typography.titleLarge)
        Text(
            "PC 风扇式温控曲线：温度达到某档阈值即切到该档（升档/降档都实时）。",
            style = MaterialTheme.typography.bodySmall,
        )

        // 温控参考温度源
        Text("温控参考温度", style = MaterialTheme.typography.labelMedium)
        ExposedDropdownMenuBox(
            expanded = sourceExpanded,
            onExpandedChange = { sourceExpanded = it },
        ) {
            OutlinedTextField(
                value = tempSource.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = sourceExpanded,
                onDismissRequest = { sourceExpanded = false },
            ) {
                TempSource.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        onClick = {
                            tempSource = s
                            sourceExpanded = false
                        },
                    )
                }
            }
        }

        // 各档位卡片
        cfg.levels.forEachIndexed { i, lvl ->
            val st = levelStates[i]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "档 ${levelName(i)}（${if (i == 0) "低于此温度" else "≥${lvl.thresholdC}°C 触发"}）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    EditableSlider(
                        label = "触发温度",
                        suffix = "°C",
                        value = st.threshold.floatValue,
                        range = 20f..70f,
                        isFloat = true,
                        onValueChange = { st.threshold.floatValue = it },
                    )
                    EditableSlider(
                        label = "制冷档",
                        suffix = "",
                        value = st.cold.floatValue,
                        range = 1f..230f,
                        onValueChange = { st.cold.floatValue = it },
                    )
                    EditableSlider(
                        label = "风速",
                        suffix = "",
                        value = st.wind.floatValue,
                        range = 4000f..7500f,
                        onValueChange = { st.wind.floatValue = it },
                    )
                }
            }
        }

        Text("采样间隔: ${(interval / 1000).toInt()}s")
        Slider(
            value = interval,
            onValueChange = { interval = it },
            valueRange = 1000f..10000f,
            steps = 8,
        )

        // ===== 界面设置（调试信息开关） =====
        Text("界面设置", style = MaterialTheme.typography.titleSmall)
        Text(
            "调试信息默认隐藏，仅在需要排查问题时开启。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("显示状态包", modifier = Modifier.weight(1f))
            Switch(checked = showStatusPacket, onCheckedChange = { showStatusPacket = it })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("显示最近命令", modifier = Modifier.weight(1f))
            Switch(checked = showRecentCommands, onCheckedChange = { showRecentCommands = it })
        }

        Row {
            Button(
                onClick = {
                    val levels = levelStates.map { st ->
                        TempLevel(
                            thresholdC = st.threshold.floatValue,
                            cold = st.cold.floatValue.toInt(),
                            wind = st.wind.floatValue.toInt(),
                        )
                    }
                    viewModel.saveConfig(
                        ControlConfig(
                            levels = levels,
                            sampleIntervalMs = interval.toLong(),
                            tempSource = tempSource,
                            showStatusPacket = showStatusPacket,
                            showRecentCommands = showRecentCommands,
                        ),
                    )
                    onBack()
                },
            ) { Text("保存") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    // 恢复出厂默认：重置各档编辑状态 + 界面开关
                    defaultLevels.forEachIndexed { i, def ->
                        val st = levelStates[i]
                        st.threshold.floatValue = def.thresholdC
                        st.cold.floatValue = def.cold.toFloat()
                        st.wind.floatValue = def.wind.toFloat()
                    }
                    interval = ControlConfig().sampleIntervalMs.toFloat()
                    tempSource = ControlConfig().tempSource
                    showStatusPacket = false
                    showRecentCommands = false
                },
            ) { Text("恢复默认") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
    }
}

/** 可编辑滑杆：滑杆拖动 + 点击数值切换为输入框直接输入。 */
@Composable
private fun EditableSlider(
    label: String,
    suffix: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    isFloat: Boolean = false,
    onValueChange: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(formatSliderValue(value, isFloat)) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$label：",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { newText ->
                        text = newText
                        // 实时解析：合法输入立即写回，保证不点"确定"直接"保存"也能拿到最新值
                        newText.toFloatOrNull()?.let { parsed ->
                            val clamped = parsed.coerceIn(range)
                            onValueChange(if (isFloat) clamped else clamped.roundToInt().toFloat())
                        }
                    },
                    singleLine = true,
                    // 温度带一位小数 → Decimal 键盘（Number 键盘没有小数点键）
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isFloat) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                    modifier = Modifier.width(110.dp),
                )
            } else {
                Text(
                    "${formatSliderValue(value, isFloat)}$suffix",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            text = formatSliderValue(value, isFloat)
                            editing = true
                        }
                        .padding(6.dp),
                )
            }
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = {
                // 整数档位取整，与"确定"保存逻辑一致（避免显示 round 与保存截断差 1）
                val v = if (isFloat) it else it.roundToInt().toFloat()
                onValueChange(v)
                text = formatSliderValue(v, isFloat)
            },
            valueRange = range,
        )
        if (editing) {
            Row {
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val parsed = text.toFloatOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(range)
                            onValueChange(if (isFloat) clamped else clamped.roundToInt().toFloat())
                            text = formatSliderValue(clamped, isFloat)
                        } else {
                            text = formatSliderValue(value, isFloat)
                        }
                        editing = false
                    },
                    modifier = Modifier.height(32.dp),
                ) { Text("确定", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

private fun formatSliderValue(v: Float, isFloat: Boolean): String =
    if (isFloat) "%.1f".format(v) else v.roundToInt().toString()

/** 一档的可编辑状态（用可变状态持有，避免每次重建） */
private class LevelEditState(
    val threshold: androidx.compose.runtime.MutableFloatState,
    val cold: androidx.compose.runtime.MutableFloatState,
    val wind: androidx.compose.runtime.MutableFloatState,
)
