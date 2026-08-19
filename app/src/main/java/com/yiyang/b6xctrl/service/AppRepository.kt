package com.yiyang.b6xctrl.service

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempLevel
import com.yiyang.b6xctrl.control.TempSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "b6x_config")

class AppRepository(private val context: Context) {

    private val keyInterval = intPreferencesKey("sample_interval_ms")
    private val keyTempSource = intPreferencesKey("temp_source")
    private val keyAutoReconnect = booleanPreferencesKey("auto_reconnect")
    private val keyShowStatusPacket = booleanPreferencesKey("show_status_packet")
    private val keyShowRecentCommands = booleanPreferencesKey("show_recent_commands")
    private val keyDeviceAddress = stringPreferencesKey("device_address")
    private val keyDeviceName = stringPreferencesKey("device_name")

    // 每档 3 个 key（阈值/制冷/风速）
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
            tempSource = TempSource.entries.getOrElse(p[keyTempSource] ?: defaults.tempSource.ordinal) { defaults.tempSource },
            autoReconnect = p[keyAutoReconnect] ?: true,
            showStatusPacket = p[keyShowStatusPacket] ?: false,
            showRecentCommands = p[keyShowRecentCommands] ?: false,
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
            p[keyAutoReconnect] = cfg.autoReconnect
            p[keyShowStatusPacket] = cfg.showStatusPacket
            p[keyShowRecentCommands] = cfg.showRecentCommands
        }
    }

    /** 已记住的散热器 MAC（连接成功后保存，用于免扫描直连/自动重连） */
    val deviceAddressFlow: Flow<String?> =
        context.dataStore.data.map { p -> p[keyDeviceAddress] }

    suspend fun saveDeviceAddress(address: String) {
        context.dataStore.edit { p -> p[keyDeviceAddress] = address }
    }

    /** 已记住的散热器型号名（首次扫描连接时保存；MAC 直连时系统读不到名字，用它显示） */
    val deviceNameFlow: Flow<String?> =
        context.dataStore.data.map { p -> p[keyDeviceName] }

    suspend fun saveDeviceName(name: String) {
        context.dataStore.edit { p -> p[keyDeviceName] = name }
    }

    companion object {
        /** 导出配置为 JSON（不含设备 MAC/名称等本地状态，纯温控设置）。 */
        fun encodeConfig(cfg: ControlConfig): String {
            val arr = JSONArray()
            cfg.levels.forEach { lvl ->
                arr.put(
                    JSONObject().apply {
                        put("threshold_c", lvl.thresholdC)
                        put("cold", lvl.cold)
                        put("wind", lvl.wind)
                    },
                )
            }
            return JSONObject()
                .put("version", 1)
                .put("levels", arr)
                .put("sample_interval_ms", cfg.sampleIntervalMs)
                .put("temp_source", cfg.tempSource.name)
                .put("auto_reconnect", cfg.autoReconnect)
                .put("show_status_packet", cfg.showStatusPacket)
                .put("show_recent_commands", cfg.showRecentCommands)
                .toString(2)
        }

        /** 解析导入的 JSON；结构不合法返回 null。字段缺失用默认值，数值越界收敛到合法范围。 */
        fun decodeConfig(json: String): ControlConfig? {
            return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("levels") ?: return null
            if (arr.length() == 0) return null
            val levels = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                TempLevel(
                    thresholdC = l.optDouble("threshold_c", 0.0).toFloat(),
                    cold = l.optInt("cold", 1).coerceIn(1, 230),
                    wind = l.optInt("wind", 4500).coerceIn(4000, 7500),
                )
            }
            ControlConfig(
                levels = levels,
                sampleIntervalMs = obj.optLong("sample_interval_ms", 2000).coerceIn(1000, 10000),
                tempSource = TempSource.entries.firstOrNull { it.name == obj.optString("temp_source") }
                    ?: TempSource.BATTERY,
                autoReconnect = obj.optBoolean("auto_reconnect", true),
                showStatusPacket = obj.optBoolean("show_status_packet", false),
                showRecentCommands = obj.optBoolean("show_recent_commands", false),
            )
        } catch (_: JSONException) {
            null
        }
        }
    }
}
