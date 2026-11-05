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

    companion object {
        val DEFAULT_WELCOME_TEXT = """
            Buổi sáng hôm ấy, mặt trời vừa ló rạng qua ngọn đồi phía xa, chiếu những tia nắng vàng óng ả xuống thung lũng. Một cơn gió nhẹ thổi qua rặng thông già, làm xào xạc những cành lá biếc.
            Thành phố thức giấc trong tiếng chim ca ríu rít và làn sương sớm còn vương vấn trên từng phiến cỏ. Cậu thanh niên khẽ mỉm cười, gấp lại cuốn tiểu thuyết dang dở và bắt đầu một ngày mới đầy hy vọng.
            Bạn có thể chạm trực tiếp vào bất kỳ câu nào trên trang sách này để đọc ngay từ câu đó, hoặc nhấn biểu tượng Thư mục trên thanh công cụ để mở file truyện .TXT từ điện thoại.
        """.trimIndent()
    }

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

        // Nhóm 1: Trạng thái phát & danh sách câu
        observerJobs += combine(
            manager.playbackStatus,
            manager.currentIndex,
            manager.sentences,
            manager.settings,
            manager.availableEngines
        ) { status, index, sentences, settings, engines ->
            appContext?.let { ReaderPreferences.saveCurrentIndex(it, index) }
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

        // Nhóm 2: Trạng thái khởi tạo, thông báo lỗi, danh sách giọng hệ thống, đoạn văn & tiêu đề sách
        observerJobs += combine(
            manager.isInitialized,
            manager.errorMessage,
            manager.bookTitle,
            manager.availableSystemVoices,
            manager.paragraphs
        ) { initialized, error, title, systemVoices, paragraphs ->
            _uiState.update { current ->
                current.copy(
                    isTtsInitialized = initialized,
                    errorMessage = error,
                    bookTitle = if (title.isNotBlank()) title else current.bookTitle,
                    availableSystemVoices = systemVoices,
                    paragraphs = paragraphs
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

        // Nhóm 4: Trạng thái Piper VITS Multi-Voice Offline (73 Giọng Rõ Chữ)
        observerJobs += combine(
            manager.piperEngine.modelStatus,
            manager.piperEngine.downloadProgress
        ) { piperStatus, piperProgress ->
            _uiState.update { current ->
                current.copy(
                    piperModelStatus = piperStatus,
                    piperDownloadProgress = piperProgress
                )
            }
        }.launchIn(viewModelScope)

        // Khôi phục bài đọc gần nhất (hoặc văn bản hướng dẫn mẫu) giống @Voice Aloud Reader
        if (manager.sentences.value.isEmpty()) {
            restoreOrInitDefaultBook(service.applicationContext, manager)
        }
    }

    private fun restoreOrInitDefaultBook(context: Context, manager: TtsManager) {
        viewModelScope.launch {
            val saved = ReaderPreferences.loadLastSession(context)
            val title = saved?.first?.ifBlank { "NovelR Reader" } ?: "Hướng dẫn đọc nhanh (@Reader)"
            val raw = saved?.second ?: DEFAULT_WELCOME_TEXT
            val startIdx = saved?.third ?: 0

            _uiState.update { it.copy(rawText = raw, bookTitle = title) }
            val parsed = withContext(Dispatchers.Default) {
                TextSplitter.parseBookContent(raw)
            }
            if (parsed.sentences.isNotEmpty() && manager.sentences.value.isEmpty()) {
                manager.setBookContent(
                    newParagraphs = parsed.paragraphs,
                    newSentences = parsed.sentences,
                    startIndex = startIdx.coerceIn(0, parsed.sentences.size - 1),
                    title = title
                )
            }
        }
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
     * Nạp trực tiếp văn bản mới (từ Clipboard hoặc hộp thoại nhập văn bản) vào trang đọc kiểu @Voice Aloud Reader.
     */
    fun loadTextContent(
        raw: String,
        title: String = "Văn bản đã dán",
        autoPlay: Boolean = false,
        onReady: () -> Unit = {}
    ) {
        if (raw.isBlank()) return
        _uiState.update { it.copy(rawText = raw, bookTitle = title, errorMessage = null) }

        viewModelScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                TextSplitter.parseBookContent(raw)
            }
            if (parsed.sentences.isNotEmpty()) {
                appContext?.let { ReaderPreferences.saveReadingSession(it, title, raw, 0) }
                ttsManager?.setBookContent(
                    newParagraphs = parsed.paragraphs,
                    newSentences = parsed.sentences,
                    startIndex = 0,
                    title = title
                )
                if (autoPlay) {
                    ttsManager?.play()
                }
                onReady()
            }
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
                loadTextContent(
                    raw = doc.content,
                    title = doc.fileName,
                    autoPlay = autoStartReading,
                    onReady = onReady
                )
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = "Không thể đọc file .txt: ${err.message}")
                }
            }
        }
    }

    fun startReading(onReady: () -> Unit = {}) {
        val raw = _uiState.value.rawText
        if (raw.isBlank()) return
        val title = _uiState.value.bookTitle.ifBlank { "Văn bản đang đọc" }
        loadTextContent(raw = raw, title = title, autoPlay = true, onReady = onReady)
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

    fun tapSentenceToRead(index: Int) {
        ttsManager?.seekAndPlay(index)
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

    fun updateWarmVoiceFilter(enabled: Boolean) {
        val newSettings = _uiState.value.settings.copy(warmVoiceFilter = enabled)
        ttsManager?.updateSettings(newSettings)
    }

    fun selectSystemVoice(voiceName: String?) {
        val newSettings = _uiState.value.settings.copy(selectedSystemVoice = voiceName)
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

    fun selectPiperVoice(voiceId: String) {
        val newSettings = _uiState.value.settings.copy(selectedPiperVoice = voiceId)
        ttsManager?.updateSettings(newSettings)
    }

    fun updateLookaheadBuffer(bufferSize: Int) {
        val newSettings = _uiState.value.settings.copy(lookaheadBufferSize = bufferSize)
        ttsManager?.updateSettings(newSettings)
    }

    fun downloadVieNeuModel() {
        ttsManager?.downloadVieNeuModel()
    }

    fun downloadPiperModel() {
        ttsManager?.downloadPiperModel()
    }
}
