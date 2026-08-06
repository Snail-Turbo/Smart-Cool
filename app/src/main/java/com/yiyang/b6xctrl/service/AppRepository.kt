package com.yiyang.b6xctrl.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "b6x_config")

class AppRepository(private val context: Context) {

    private val keyStart = floatPreferencesKey("start_temp")
    private val keyStop = floatPreferencesKey("stop_temp")
    private val keyInterval = intPreferencesKey("sample_interval_ms")
    private val keyBaseWind = intPreferencesKey("base_wind")
    private val keyBaseCold = intPreferencesKey("base_cold")
    private val keyTempSource = intPreferencesKey("temp_source")

    val configFlow: Flow<ControlConfig> = context.dataStore.data.map { p ->
        ControlConfig(
            startTemp = p[keyStart] ?: 42f,
            stopTemp = p[keyStop] ?: 36f,
            baseWindLevel = p[keyBaseWind] ?: 140,
            baseColdLevel = p[keyBaseCold] ?: 140,
            sampleIntervalMs = p[keyInterval]?.toLong() ?: 2000L,
            tempSource = TempSource.entries.getOrElse(p[keyTempSource] ?: 0) { TempSource.CPU_GPU_MAX },
        )
    }

    suspend fun saveConfig(cfg: ControlConfig) {
        context.dataStore.edit { p ->
            p[keyStart] = cfg.startTemp
            p[keyStop] = cfg.stopTemp
            p[keyInterval] = cfg.sampleIntervalMs.toInt()
            p[keyBaseWind] = cfg.baseWindLevel
            p[keyBaseCold] = cfg.baseColdLevel
            p[keyTempSource] = cfg.tempSource.ordinal
        }
    }
}
