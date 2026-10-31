package com.novelr.reader

import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.ReaderUiState
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.service.vieneu.VieNeuConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VieNeuIntegrationTest {

    @Test
    fun `VieNeuConstants should define valid preset voices and sample rate`() {
        assertEquals(48000, VieNeuConstants.SAMPLE_RATE)
        assertEquals(4, VieNeuConstants.PRESET_VOICES.size)

        val haiDang = VieNeuConstants.PRESET_VOICES.find { it.id == "hai_dang" }
        assertNotNull(haiDang)
        assertEquals("Hải Đăng", haiDang?.name)
        assertEquals("Nam", haiDang?.gender)

        val lanNhi = VieNeuConstants.PRESET_VOICES.find { it.id == "lan_nhi" }
        assertNotNull(lanNhi)
        assertEquals("Lan Nhi", lanNhi?.name)
        assertEquals("Nữ", lanNhi?.gender)
    }

    @Test
    fun `TtsSettings should support switching between Native and VieNeu engines`() {
        val defaultSettings = TtsSettings()
        assertEquals(TtsEngineType.NATIVE_SYSTEM, defaultSettings.engineType)
        assertEquals(2, defaultSettings.lookaheadBufferSize)

        val vieneuSettings = defaultSettings.copy(
            engineType = TtsEngineType.VIENEU_ON_DEVICE,
            selectedVieNeuVoice = "lan_nhi",
            lookaheadBufferSize = 4,
            speechRate = 1.25f
        )
        assertEquals(TtsEngineType.VIENEU_ON_DEVICE, vieneuSettings.engineType)
        assertEquals("lan_nhi", vieneuSettings.selectedVieNeuVoice)
        assertEquals(4, vieneuSettings.lookaheadBufferSize)
        assertEquals(1.25f, vieneuSettings.speechRate, 0.001f)
    }

    @Test
    fun `ReaderUiState should accurately track prebuffered sentence indices`() {
        val state = ReaderUiState(
            currentIndex = 3,
            playbackStatus = PlaybackStatus.PLAYING,
            prebufferedIndices = setOf(2, 3, 4, 5)
        )
        assertTrue(state.prebufferedIndices.contains(4))
        assertTrue(state.prebufferedIndices.contains(5))
        assertEquals(4, state.prebufferedIndices.size)
    }
}
