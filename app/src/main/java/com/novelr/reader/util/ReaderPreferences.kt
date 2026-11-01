package com.novelr.reader.util

import android.content.Context
import com.novelr.reader.model.ReadingTheme
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings

object ReaderPreferences {
    private const val PREF_NAME = "novelr_reader_prefs"

    private const val KEY_ENGINE_TYPE = "engine_type"
    private const val KEY_VIENEU_VOICE = "vieneu_voice"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_PITCH = "pitch"
    private const val KEY_SENTENCE_PAUSE = "sentence_pause_ms"
    private const val KEY_CLAUSE_PAUSE = "clause_pause_ms"
    private const val KEY_LOOKAHEAD = "lookahead_buffer"
    private const val KEY_BG_PLAYBACK = "background_playback"
    private const val KEY_ENGINE_PKG = "engine_package"
    private const val KEY_FONT_SIZE = "font_size_sp"
    private const val KEY_READING_THEME = "reading_theme"

    fun loadSettings(context: Context): TtsSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val engineTypeName = prefs.getString(KEY_ENGINE_TYPE, TtsEngineType.NATIVE_SYSTEM.name)
        val engineType = try {
            TtsEngineType.valueOf(engineTypeName ?: TtsEngineType.NATIVE_SYSTEM.name)
        } catch (_: Exception) {
            TtsEngineType.NATIVE_SYSTEM
        }

        return TtsSettings(
            speechRate = prefs.getFloat(KEY_SPEECH_RATE, TtsSettings.DEFAULT_SPEECH_RATE).coerceIn(0.5f, 2.0f),
            pitch = prefs.getFloat(KEY_PITCH, TtsSettings.DEFAULT_PITCH).coerceIn(0.5f, 1.5f),
            enginePackage = prefs.getString(KEY_ENGINE_PKG, null),
            engineType = engineType,
            selectedVieNeuVoice = prefs.getString(KEY_VIENEU_VOICE, TtsSettings.DEFAULT_VIENEU_VOICE)
                ?: TtsSettings.DEFAULT_VIENEU_VOICE,
            lookaheadBufferSize = prefs.getInt(KEY_LOOKAHEAD, TtsSettings.DEFAULT_LOOKAHEAD).coerceIn(1, 4),
            sentencePauseMs = prefs.getInt(KEY_SENTENCE_PAUSE, TtsSettings.DEFAULT_SENTENCE_PAUSE_MS).coerceIn(200, 1200),
            clausePauseMs = prefs.getInt(KEY_CLAUSE_PAUSE, TtsSettings.DEFAULT_CLAUSE_PAUSE_MS).coerceIn(120, 600),
            backgroundPlayback = prefs.getBoolean(KEY_BG_PLAYBACK, true)
        )
    }

    fun saveSettings(context: Context, settings: TtsSettings) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ENGINE_TYPE, settings.engineType.name)
            .putString(KEY_VIENEU_VOICE, settings.selectedVieNeuVoice)
            .putFloat(KEY_SPEECH_RATE, settings.speechRate)
            .putFloat(KEY_PITCH, settings.pitch)
            .putInt(KEY_SENTENCE_PAUSE, settings.sentencePauseMs)
            .putInt(KEY_CLAUSE_PAUSE, settings.clausePauseMs)
            .putInt(KEY_LOOKAHEAD, settings.lookaheadBufferSize)
            .putBoolean(KEY_BG_PLAYBACK, settings.backgroundPlayback)
            .putString(KEY_ENGINE_PKG, settings.enginePackage)
            .apply()
    }

    fun loadFontSize(context: Context): Float {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_FONT_SIZE, 19f).coerceIn(14f, 32f)
    }

    fun saveFontSize(context: Context, fontSizeSp: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FONT_SIZE, fontSizeSp)
            .apply()
    }

    fun loadReadingTheme(context: Context): ReadingTheme {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_READING_THEME, ReadingTheme.SEPIA.name)
        return try {
            ReadingTheme.valueOf(name ?: ReadingTheme.SEPIA.name)
        } catch (_: Exception) {
            ReadingTheme.SEPIA
        }
    }

    fun saveReadingTheme(context: Context, theme: ReadingTheme) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_READING_THEME, theme.name)
            .apply()
    }
}
