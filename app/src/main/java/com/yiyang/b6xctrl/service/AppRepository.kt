package com.yiyang.b6xctrl.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempLevel
import com.yiyang.b6xctrl.control.TempSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "b6x_config")

class AppRepository(private val context: Context) {

    private val keyInterval = intPreferencesKey("sample_interval_ms")
    private val keyTempSource = intPreferencesKey("temp_source")

    // 4 档：每档 3 个 key（阈值/制冷/风速）
    private fun keyTh(i: Int) = floatPreferencesKey("level_${i}_threshold")
    private fun keyCold(i: Int) = intPreferencesKey("level_${i}_cold")
    private fun keyWind(i: Int) = intPreferencesKey("level_${i}_wind")

    private val defaults = ControlConfig()

    val configFlow: Flow<ControlConfig> = context.dataStore.data.map { p ->
        val levels = defaults.levels.mapIndexed { i, def ->
            TempLevel(
                thresholdC = p[keyTh(i)] ?: def.thresholdC,
                cold = p[keyCold(i)] ?: def.cold,
                wind = p[keyWind(i)] ?: def.wind,
            )
        }
        ControlConfig(
            levels = levels,
            sampleIntervalMs = p[keyInterval]?.toLong() ?: defaults.sampleIntervalMs,
            tempSource = TempSource.entries.getOrElse(p[keyTempSource] ?: 0) { TempSource.CPU_GPU_MAX },
        )
    }

    suspend fun saveConfig(cfg: ControlConfig) {
        context.dataStore.edit { p ->
            cfg.levels.forEachIndexed { i, lvl ->
                p[keyTh(i)] = lvl.thresholdC
                p[keyCold(i)] = lvl.cold
                p[keyWind(i)] = lvl.wind
            }
            p[keyInterval] = cfg.sampleIntervalMs.toInt()
            p[keyTempSource] = cfg.tempSource.ordinal
        }
    }
}
