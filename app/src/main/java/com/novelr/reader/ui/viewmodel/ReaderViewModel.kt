package com.novelr.reader.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.ReaderUiState
import com.novelr.reader.model.ReadingTheme
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.service.TtsAudioService
import com.novelr.reader.service.TtsManager
import com.novelr.reader.util.ReaderPreferences
import com.novelr.reader.util.TextSplitter
import com.novelr.reader.util.TxtFileLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var ttsManager: TtsManager? = null
    private var appContext: Context? = null
    private val observerJobs = mutableListOf<Job>()

    fun attachService(service: TtsAudioService) {
        val manager = service.ttsManager
        if (this.ttsManager === manager && observerJobs.isNotEmpty()) return
        this.ttsManager = manager
        this.appContext = service.applicationContext

        val savedFont = ReaderPreferences.loadFontSize(service.applicationContext)
        val savedTheme = ReaderPreferences.loadReadingTheme(service.applicationContext)
        _uiState.update { it.copy(fontSizeSp = savedFont, readingTheme = savedTheme) }

        observerJobs.forEach { it.cancel() }
        observerJobs.clear()

        // Nhóm 1: Trạng thái phát & danh sách câu (tối đa 5 flows để dùng typed combine chuẩn của Kotlin)
        observerJobs += combine(
            manager.playbackStatus,
            manager.currentIndex,
            manager.sentences,
            manager.settings,
            manager.availableEngines
        ) { status, index, sentences, settings, engines ->
            _uiState.update { current ->
                current.copy(
                    playbackStatus = status,
                    currentIndex = index,
                    sentences = sentences,
                    settings = settings,
                    availableEngines = engines
                )
            }
        }.launchIn(viewModelScope)

        // Nhóm 2: Trạng thái khởi tạo, thông báo lỗi & tiêu đề sách/file .txt
        observerJobs += combine(
            manager.isInitialized,
            manager.errorMessage,
            manager.bookTitle
        ) { initialized, error, title ->
            _uiState.update { current ->
                current.copy(
                    isTtsInitialized = initialized,
                    errorMessage = error,
                    bookTitle = if (title.isNotBlank()) title else current.bookTitle
                )
            }
        }.launchIn(viewModelScope)

        // Nhóm 3: Trạng thái VieNeu AI On-Device & Pre-buffering
        observerJobs += combine(
            manager.vieneuEngine.modelStatus,
            manager.vieneuEngine.downloadProgress,
            manager.vieneuPreBufferQueue.prebufferedIndices,
            manager.vieneuPreBufferQueue.isSynthesizing
        ) { status, progress, buffered, synthesizing ->
            _uiState.update { current ->
                current.copy(
                    vieneuModelStatus = status,
                    vieneuDownloadProgress = progress,
                    prebufferedIndices = buffered,
                    isSynthesizing = synthesizing
                )
            }
        }.launchIn(viewModelScope)
    }

    fun onTextChanged(newText: String) {
        _uiState.update {
            it.copy(
                rawText = newText,
                bookTitle = if (newText.isBlank()) "" else it.bookTitle
            )
        }
    }

    /**
     * Nạp nội dung từ file .txt trong bộ nhớ điện thoại (hỗ trợ UTF-8, UTF-16, Windows-1258).
     */
    fun loadTxtFile(
        context: Context,
        uri: Uri,
        autoStartReading: Boolean = false,
        onReady: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                TxtFileLoader.loadFromUri(context, uri)
            }
            result.onSuccess { doc ->
                _uiState.update {
                    it.copy(
                        rawText = doc.content,
                        bookTitle = doc.fileName,
                        errorMessage = null
                    )
                }
                if (autoStartReading && doc.content.isNotBlank()) {
                    startReading(onReady = onReady)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = "Không thể đọc file .txt: ${err.message}")
                }
            }
        }
    }

    /**
     * Tách câu bất đồng bộ trên Dispatchers.Default để tránh giật lag (ANR) trên Main Thread
     * khi người dùng dán chương truyện hoặc mở file .txt dài hàng chục nghìn từ.
     */
    fun startReading(onReady: () -> Unit = {}) {
        val raw = _uiState.value.rawText
        if (raw.isBlank()) return
        val title = _uiState.value.bookTitle.ifBlank { "Văn bản đang đọc" }

        viewModelScope.launch {
            val sentences = withContext(Dispatchers.Default) {
                TextSplitter.splitIntoSentences(raw)
            }
            if (sentences.isNotEmpty()) {
                ttsManager?.setSentences(sentences, startIndex = 0, title = title)
                ttsManager?.play()
                onReady()
            }
        }
    }

    fun togglePlayPause() {
        val currentStatus = _uiState.value.playbackStatus
        if (currentStatus == PlaybackStatus.PLAYING) {
            ttsManager?.pause()
        } else {
            ttsManager?.play()
        }
    }

    fun nextSentence() {
        ttsManager?.next()
    }

    fun previousSentence() {
        ttsManager?.previous()
    }

    fun seekToSentence(index: Int) {
        ttsManager?.seekTo(index)
    }

    fun increaseFontSize() {
        _uiState.update { state ->
            val next = (state.fontSizeSp + 2f).coerceAtMost(32f)
            appContext?.let { ReaderPreferences.saveFontSize(it, next) }
            state.copy(fontSizeSp = next)
        }
    }

    fun decreaseFontSize() {
        _uiState.update { state ->
            val next = (state.fontSizeSp - 2f).coerceAtLeast(14f)
            appContext?.let { ReaderPreferences.saveFontSize(it, next) }
            state.copy(fontSizeSp = next)
        }
    }

    fun updateReadingTheme(theme: ReadingTheme) {
        _uiState.update { state ->
            appContext?.let { ReaderPreferences.saveReadingTheme(it, theme) }
            state.copy(readingTheme = theme)
        }
    }

    fun updateSpeechRate(rate: Float) {
        val newSettings = _uiState.value.settings.copy(speechRate = rate)
        ttsManager?.updateSettings(newSettings)
    }

    fun updatePitch(pitch: Float) {
        val newSettings = _uiState.value.settings.copy(pitch = pitch)
        ttsManager?.updateSettings(newSettings)
    }

    fun updateSentencePause(pauseMs: Int) {
        val newSettings = _uiState.value.settings.copy(sentencePauseMs = pauseMs.coerceIn(200, 1200))
        ttsManager?.updateSettings(newSettings)
    }

    fun updateBackgroundPlayback(enabled: Boolean) {
        val newSettings = _uiState.value.settings.copy(backgroundPlayback = enabled)
        ttsManager?.updateSettings(newSettings)
    }

    fun resetVoiceSettingsToDefault() {
        ttsManager?.resetVoiceSettingsToDefault()
    }

    fun selectEngine(enginePackage: String) {
        val newSettings = _uiState.value.settings.copy(enginePackage = enginePackage)
        ttsManager?.updateSettings(newSettings)
    }

    fun selectEngineType(engineType: TtsEngineType) {
        val newSettings = _uiState.value.settings.copy(engineType = engineType)
        ttsManager?.updateSettings(newSettings)
    }

    fun selectVieNeuVoice(voiceId: String) {
        val newSettings = _uiState.value.settings.copy(selectedVieNeuVoice = voiceId)
        ttsManager?.updateSettings(newSettings)
    }

    fun updateLookaheadBuffer(bufferSize: Int) {
        val newSettings = _uiState.value.settings.copy(lookaheadBufferSize = bufferSize)
        ttsManager?.updateSettings(newSettings)
    }

    fun downloadVieNeuModel() {
        ttsManager?.downloadVieNeuModel()
    }
}
