package com.novelr.reader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.ReaderUiState
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.service.TtsAudioService
import com.novelr.reader.service.TtsManager
import com.novelr.reader.util.TextSplitter
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
    private val observerJobs = mutableListOf<Job>()

    fun attachService(service: TtsAudioService) {
        val manager = service.ttsManager
        if (this.ttsManager === manager && observerJobs.isNotEmpty()) return
        this.ttsManager = manager

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

        // Nhóm 2: Trạng thái khởi tạo & thông báo lỗi
        observerJobs += combine(
            manager.isInitialized,
            manager.errorMessage
        ) { initialized, error ->
            _uiState.update { current ->
                current.copy(
                    isTtsInitialized = initialized,
                    errorMessage = error
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
        _uiState.update { it.copy(rawText = newText) }
    }

    /**
     * Tách câu bất đồng bộ trên Dispatchers.Default để tránh giật lag (ANR) trên Main Thread
     * khi người dùng dán chương truyện dài hàng chục nghìn từ.
     */
    fun startReading(onReady: () -> Unit = {}) {
        val raw = _uiState.value.rawText
        if (raw.isBlank()) return

        viewModelScope.launch {
            val sentences = withContext(Dispatchers.Default) {
                TextSplitter.splitIntoSentences(raw)
            }
            if (sentences.isNotEmpty()) {
                ttsManager?.setSentences(sentences, startIndex = 0)
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
        _uiState.update { it.copy(fontSizeSp = (it.fontSizeSp + 2f).coerceAtMost(32f)) }
    }

    fun decreaseFontSize() {
        _uiState.update { it.copy(fontSizeSp = (it.fontSizeSp - 2f).coerceAtLeast(12f)) }
    }

    fun updateSpeechRate(rate: Float) {
        val newSettings = _uiState.value.settings.copy(speechRate = rate)
        ttsManager?.updateSettings(newSettings)
    }

    fun updatePitch(pitch: Float) {
        val newSettings = _uiState.value.settings.copy(pitch = pitch)
        ttsManager?.updateSettings(newSettings)
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
