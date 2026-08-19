package com.yiyang.b6xctrl

import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.control.TempLevel
import com.yiyang.b6xctrl.control.TempSource
import com.yiyang.b6xctrl.service.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppRepositoryConfigCodecTest {

    @Test
    fun encodeDecode_roundTrip() {
        val cfg = ControlConfig(
            levels = listOf(
                TempLevel(30f, 1, 4500),
                TempLevel(33f, 40, 4600),
                TempLevel(36f, 170, 5804),
                TempLevel(38f, 180, 6000),
                TempLevel(39f, 200, 7000),
            ),
            sampleIntervalMs = 3000,
            tempSource = TempSource.CPU_GPU_MAX,
            autoReconnect = false,
            showStatusPacket = true,
            showRecentCommands = true,
        )
        val decoded = AppRepository.decodeConfig(AppRepository.encodeConfig(cfg))
        assertNotNull(decoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun decode_invalidJson_returnsNull() {
        assertNull(AppRepository.decodeConfig("not json"))
        assertNull(AppRepository.decodeConfig("{}"))
        assertNull(AppRepository.decodeConfig("{\"levels\": []}"))
    }

    @Test
    fun decode_clampsOutOfRangeAndFallsBack() {
        val json = """{"levels":[{"threshold_c":25,"cold":999,"wind":100}],"sample_interval_ms":1,"temp_source":"UNKNOWN"}"""
        val decoded = AppRepository.decodeConfig(json)
        assertNotNull(decoded)
        assertEquals(230, decoded!!.levels[0].cold) // 越界收敛到上限
        assertEquals(4000, decoded.levels[0].wind)  // 越界收敛到下限
        assertEquals(1000, decoded.sampleIntervalMs)
        assertEquals(TempSource.BATTERY, decoded.tempSource) // 未知源回退默认
    }
}
