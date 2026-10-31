package com.novelr.reader

import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.ReaderUiState
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.vieneu.VieNeuConstants
import com.novelr.reader.service.vieneu.VieNeuEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VieNeuIntegrationTest {

    @Test
    fun `VieNeuConstants should define valid v3 Nano preset voices and 24kHz sample rate`() {
        assertEquals(24000, VieNeuConstants.SAMPLE_RATE)
        assertEquals(6, VieNeuConstants.PRESET_VOICES.size)

        val ducTri = VieNeuConstants.PRESET_VOICES.find { it.id == "duc_tri" }
        assertNotNull(ducTri)
        assertEquals("Đức Trí", ducTri?.name)

        val myDuyen = VieNeuConstants.PRESET_VOICES.find { it.id == "my_duyen" }
        assertNotNull(myDuyen)
        assertEquals("Mỹ Duyên", myDuyen?.name)

        assertEquals(4, VieNeuEngine.MODEL_SPECS.size)
        assertTrue(VieNeuEngine.TOTAL_EXPECTED_BYTES > 250_000_000L)
    }

    @Test
    fun `TtsSettings should support switching between Native and VieNeu engines`() {
        val defaultSettings = TtsSettings()
        assertEquals(TtsEngineType.NATIVE_SYSTEM, defaultSettings.engineType)
        assertEquals("duc_tri", defaultSettings.selectedVieNeuVoice)
        assertEquals(2, defaultSettings.lookaheadBufferSize)

        val vieneuSettings = defaultSettings.copy(
            engineType = TtsEngineType.VIENEU_ON_DEVICE,
            selectedVieNeuVoice = "my_duyen",
            lookaheadBufferSize = 4,
            speechRate = 1.25f
        )
        assertEquals(TtsEngineType.VIENEU_ON_DEVICE, vieneuSettings.engineType)
        assertEquals("my_duyen", vieneuSettings.selectedVieNeuVoice)
        assertEquals(4, vieneuSettings.lookaheadBufferSize)
        assertEquals(1.25f, vieneuSettings.speechRate, 0.001f)
    }

    @Test
    fun `ReaderUiState should accurately track prebuffered sentence indices and model status`() {
        val state = ReaderUiState(
            currentIndex = 3,
            playbackStatus = PlaybackStatus.PLAYING,
            prebufferedIndices = setOf(2, 3, 4, 5)
        )
        assertEquals(VieNeuModelStatus.NOT_DOWNLOADED, state.vieneuModelStatus)
        assertTrue(state.prebufferedIndices.contains(4))
        assertTrue(state.prebufferedIndices.contains(5))
        assertEquals(4, state.prebufferedIndices.size)
    }
}
