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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    val cfg = ui.config

    var startTemp by remember { mutableFloatStateOf(cfg.startTemp) }
    var stopTemp by remember { mutableFloatStateOf(cfg.stopTemp) }
    var interval by remember { mutableFloatStateOf(cfg.sampleIntervalMs.toFloat()) }
    var tempSource by remember { mutableStateOf(cfg.tempSource) }
    var sourceExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("温控设置", style = MaterialTheme.typography.titleLarge)

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

        Text("启动阈值: ${"%.0f".format(startTemp)}°C")
        Slider(
            value = startTemp,
            onValueChange = { startTemp = it },
            valueRange = 30f..55f,
        )

        Text("停止阈值: ${"%.0f".format(stopTemp)}°C")
        Slider(
            value = stopTemp,
            onValueChange = { stopTemp = it },
            valueRange = 30f..55f,
        )

        Text("采样间隔: ${(interval / 1000).toInt()}s")
        Slider(
            value = interval,
            onValueChange = { interval = it },
            valueRange = 1000f..10000f,
            steps = 8,
        )

        Row {
            Button(
                onClick = {
                    viewModel.saveConfig(
                        ControlConfig(
                            startTemp = startTemp,
                            stopTemp = stopTemp,
                            baseWindLevel = cfg.baseWindLevel,
                            baseColdLevel = cfg.baseColdLevel,
                            windMap = cfg.windMap,
                            coldMap = cfg.coldMap,
                            sampleIntervalMs = interval.toLong(),
                            tempSource = tempSource,
                        ),
                    )
                    onBack()
                },
            ) { Text("保存") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
    }
}
