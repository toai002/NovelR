package com.novelr.reader

import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.ReaderUiState
import com.novelr.reader.model.ReadingTheme
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.vieneu.VieNeuConstants
import com.novelr.reader.service.vieneu.VieNeuEngine
import com.novelr.reader.util.TxtFileLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `TtsSettings should support pause customization, background playback and reset to default`() {
        val defaultSettings = TtsSettings()
        assertEquals(TtsEngineType.NATIVE_SYSTEM, defaultSettings.engineType)
        assertEquals("duc_tri", defaultSettings.selectedVieNeuVoice)
        assertEquals(550, defaultSettings.sentencePauseMs)
        assertEquals(240, defaultSettings.clausePauseMs)
        assertTrue(defaultSettings.backgroundPlayback)
        assertTrue(defaultSettings.isDefaultVoiceConfig)

        val customizedSettings = defaultSettings.copy(
            engineType = TtsEngineType.VIENEU_ON_DEVICE,
            selectedVieNeuVoice = "my_duyen",
            lookaheadBufferSize = 4,
            speechRate = 1.4f,
            pitch = 1.15f,
            sentencePauseMs = 800
        )
        assertFalse(customizedSettings.isDefaultVoiceConfig)

        val resetSettings = customizedSettings.resetVoiceToDefault()
        assertEquals(TtsEngineType.VIENEU_ON_DEVICE, resetSettings.engineType)
        assertEquals("duc_tri", resetSettings.selectedVieNeuVoice)
        assertEquals(1.0f, resetSettings.speechRate, 0.001f)
        assertEquals(1.0f, resetSettings.pitch, 0.001f)
        assertEquals(550, resetSettings.sentencePauseMs)
        assertEquals(TtsSettings.DEFAULT_LOOKAHEAD, resetSettings.lookaheadBufferSize)
        assertTrue(resetSettings.isDefaultVoiceConfig)
    }

    @Test
    fun `TxtFileLoader should decode UTF-8 and UTF-8 BOM Vietnamese text`() {
        val sample = "Xin chào Việt Nam! Đọc truyện tiếng Việt."
        val utf8Bytes = sample.toByteArray(Charsets.UTF_8)
        assertEquals(sample, TxtFileLoader.decodeVietnameseBytes(utf8Bytes))

        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8Bytes
        assertEquals(sample, TxtFileLoader.decodeVietnameseBytes(bomBytes))
    }

    @Test
    fun `ReaderUiState should accurately track prebuffered sentence indices, theme and model status`() {
        val state = ReaderUiState(
            currentIndex = 3,
            bookTitle = "TruyenKiemHiep.txt",
            readingTheme = ReadingTheme.SEPIA,
            playbackStatus = PlaybackStatus.PLAYING,
            prebufferedIndices = setOf(2, 3, 4, 5)
        )
        assertEquals(VieNeuModelStatus.NOT_DOWNLOADED, state.vieneuModelStatus)
        assertEquals(ReadingTheme.SEPIA, state.readingTheme)
        assertEquals("TruyenKiemHiep.txt", state.bookTitle)
        assertTrue(state.prebufferedIndices.contains(4))
        assertTrue(state.prebufferedIndices.contains(5))
        assertEquals(4, state.prebufferedIndices.size)
    }
}
