package com.novelr.reader.service

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.novelr.reader.model.EngineInfo
import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.SystemVoiceInfo
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.edgetts.EdgeTtsEngine
import com.novelr.reader.service.piper.PiperEngine
import com.novelr.reader.service.vieneu.VieNeuAudioPlayer
import com.novelr.reader.service.vieneu.VieNeuEngine
import com.novelr.reader.service.vieneu.VieNeuPreBufferQueue
import com.novelr.reader.util.ReaderParagraph
import com.novelr.reader.util.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class TtsManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_PREFIX = "novelr_sentence_"

        // Thứ tự ưu tiên các giọng Tiếng Việt ấm, ít chói nhất của Google TTS khi bật bộ lọc Làm ấm giọng
        private val PREFERRED_WARM_VOICE_KEYWORDS = listOf(
            "vi-vn-x-vid-local",
            "vi-vn-x-vic-local",
            "vi-vn-x-vie-local",
            "vi-vn-x-vid",
            "vi-vn-x-vic",
            "vi-vn-x-vie"
        )
    }

    // --- Audio Session & Hardware De-Esser / Anti-Harshness DSP ---
    private val sharedAudioSessionId: Int = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.generateAudioSessionId() ?: 0
    } catch (_: Exception) {
        0
    }
    private val warmAudioDsp = WarmAudioDspProcessor(sharedAudioSessionId)

    // --- Native TTS Engine ---
    private var textToSpeech: TextToSpeech? = null

    // --- VieNeu AI On-Device Engine (v3 Nano ONNX 24kHz) ---
    val vieneuEngine = VieNeuEngine(context)

    // --- Piper VITS Multi-Voice Offline Engine (73 Giọng Rõ Chữ) ---
    val piperEngine = PiperEngine(context)

    // --- Microsoft Edge TTS Direct Online Engine (Hoài My, Nam Minh + 6 Giọng Đa Ngôn Ngữ) ---
    val edgeTtsEngine = EdgeTtsEngine(context)

    private val vieneuAudioPlayer = VieNeuAudioPlayer(scope, sharedAudioSessionId)
    val vieneuPreBufferQueue = VieNeuPreBufferQueue(vieneuEngine, piperEngine, edgeTtsEngine, scope)

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.IDLE)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _sentences = MutableStateFlow<List<String>>(emptyList())
    val sentences: StateFlow<List<String>> = _sentences.asStateFlow()

    private val _paragraphs = MutableStateFlow<List<ReaderParagraph>>(emptyList())
    val paragraphs: StateFlow<List<ReaderParagraph>> = _paragraphs.asStateFlow()

    private val _bookTitle = MutableStateFlow("")
    val bookTitle: StateFlow<String> = _bookTitle.asStateFlow()

    private val _availableEngines = MutableStateFlow<List<EngineInfo>>(emptyList())
    val availableEngines: StateFlow<List<EngineInfo>> = _availableEngines.asStateFlow()

    private val _availableSystemVoices = MutableStateFlow<List<SystemVoiceInfo>>(emptyList())
    val availableSystemVoices: StateFlow<List<SystemVoiceInfo>> = _availableSystemVoices.asStateFlow()

    private val _settings = MutableStateFlow(ReaderPreferences.loadSettings(context))
    val settings: StateFlow<TtsSettings> = _settings.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val vietnameseLocale = Locale("vi", "VN")
    private var vieneuSynthesisJob: Job? = null

    init {
        initializeNativeTts(enginePackage = _settings.value.enginePackage)

        // Giải phóng RAM của bộ máy không được chọn khi mới mở app
        when (_settings.value.engineType) {
            TtsEngineType.PIPER_ON_DEVICE -> vieneuEngine.unloadSessions()
            TtsEngineType.VIENEU_ON_DEVICE -> piperEngine.unloadSessions()
            TtsEngineType.EDGE_ONLINE,
            TtsEngineType.NATIVE_SYSTEM -> {
                vieneuEngine.unloadSessions()
                piperEngine.unloadSessions()
            }
        }

        // Khi model VieNeu-TTS v3 Nano vừa tải xong, tự động kích hoạt prefetch các câu tiếp theo vào RAM
        scope.launch {
            vieneuEngine.modelStatus.collect { status ->
                if (status == VieNeuModelStatus.READY &&
                    _settings.value.engineType == TtsEngineType.VIENEU_ON_DEVICE &&
                    _sentences.value.isNotEmpty()
                ) {
                    vieneuPreBufferQueue.triggerPrefetch(
                        startIndex = _currentIndex.value,
                        sentences = _sentences.value,
                        settings = _settings.value
                    )
                }
            }
        }

        // Khi model Piper VITS vừa tải xong, tự động kích hoạt prefetch các câu tiếp theo vào RAM
        scope.launch {
            piperEngine.modelStatus.collect { status ->
                if (status == VieNeuModelStatus.READY &&
                    _settings.value.engineType == TtsEngineType.PIPER_ON_DEVICE &&
                    _sentences.value.isNotEmpty()
                ) {
                    vieneuPreBufferQueue.triggerPrefetch(
                        startIndex = _currentIndex.value,
                        sentences = _sentences.value,
                        settings = _settings.value
                    )
                }
            }
        }
    }

    private fun isOnDeviceNeuralEngineActive(settings: TtsSettings = _settings.value): Boolean {
        return settings.engineType == TtsEngineType.VIENEU_ON_DEVICE ||
                settings.engineType == TtsEngineType.PIPER_ON_DEVICE ||
                settings.engineType == TtsEngineType.EDGE_ONLINE
    }

    private fun isActiveNeuralModelReady(settings: TtsSettings = _settings.value): Boolean {
        return when (settings.engineType) {
            TtsEngineType.EDGE_ONLINE -> true
            TtsEngineType.VIENEU_ON_DEVICE -> vieneuEngine.modelStatus.value == VieNeuModelStatus.READY
            TtsEngineType.PIPER_ON_DEVICE -> piperEngine.modelStatus.value == VieNeuModelStatus.READY
            TtsEngineType.NATIVE_SYSTEM -> false
        }
    }

    fun initializeNativeTts(enginePackage: String?) {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        _isInitialized.value = false

        textToSpeech = TextToSpeech(
            context.applicationContext,
            { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onNativeTtsInitSuccess()
                } else {
                    val err = "Khởi tạo TTS thất bại (mã lỗi: $status)"
                    Log.e(TAG, err)
                    _errorMessage.value = err
                }
            },
            enginePackage
        )
    }

    private fun onNativeTtsInitSuccess() {
        val tts = textToSpeech ?: return

        val engines = tts.engines?.map { engine ->
            EngineInfo(label = engine.label ?: engine.name, packageName = engine.name)
        } ?: emptyList()
        _availableEngines.value = engines

        val langResult = tts.setLanguage(vietnameseLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val warn = "Thiếu gói ngôn ngữ Tiếng Việt (vi-VN) trên máy. Bạn có thể chuyển sang Piper Rõ Chữ hoặc VieNeu AI."
            Log.w(TAG, warn)
            _errorMessage.value = warn
            tts.language = Locale.getDefault()
        } else {
            _errorMessage.value = null
        }

        discoverVietnameseSystemVoices(tts)
        applySettingsToNative(tts, _settings.value)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                scope.launch {
                    _playbackStatus.value = PlaybackStatus.PLAYING
                }
            }

            override fun onDone(utteranceId: String?) {
                scope.launch {
                    handleUtteranceDone(utteranceId)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scope.launch {
                    _errorMessage.value = "Lỗi phát âm tại câu $utteranceId"
                    _playbackStatus.value = PlaybackStatus.PAUSED
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                scope.launch {
                    _errorMessage.value = "Lỗi TTS ($errorCode) tại câu $utteranceId"
                    _playbackStatus.value = PlaybackStatus.PAUSED
                }
            }
        })

        _isInitialized.value = true
    }

    /**
     * Quét danh sách các biến thể giọng Tiếng Việt có sẵn trong hệ thống (Nam trầm, Nữ dịu...)
     * và đặt nhãn thân thiện để người dùng chọn giọng ít chói tai nhất.
     */
    private fun discoverVietnameseSystemVoices(tts: TextToSpeech) {
        try {
            val allVoices = tts.voices ?: emptySet()
            val viVoices = allVoices.filter { voice ->
                voice.locale != null && voice.locale.language.startsWith("vi", ignoreCase = true)
            }

            val mapped = viVoices.mapIndexed { idx, voice ->
                val rawName = voice.name ?: "vi_voice_$idx"
                val lower = rawName.lowercase()
                val isOffline = !voice.isNetworkConnectionRequired
                val modeSuffix = if (isOffline) "Offline" else "Online"

                val friendlyBase = when {
                    lower.contains("vid") -> "Nam Trầm Ấm ($modeSuffix • Đỡ chói)"
                    lower.contains("vie") -> "Nam Tự Nhiên ($modeSuffix)"
                    lower.contains("vic") -> "Nữ Dịu Dàng ($modeSuffix • Êm tai)"
                    lower.contains("gft") -> "Nữ Sáng Rõ ($modeSuffix)"
                    lower.contains("language") -> "Mặc Định Hệ Thống ($modeSuffix)"
                    else -> "Giọng Việt ${idx + 1} ($rawName)"
                }

                SystemVoiceInfo(
                    name = rawName,
                    label = friendlyBase,
                    isOffline = isOffline
                )
            }.sortedWith(
                compareByDescending<SystemVoiceInfo> { it.isOffline }
                    .thenBy { info ->
                        val l = info.name.lowercase()
                        when {
                            l.contains("vid") -> 0
                            l.contains("vic") -> 1
                            l.contains("vie") -> 2
                            else -> 3
                        }
                    }
            )

            _availableSystemVoices.value = mapped
        } catch (e: Exception) {
            Log.w(TAG, "Không thể quét danh sách giọng hệ thống: ${e.message}")
            _availableSystemVoices.value = emptyList()
        }
    }

    private fun handleUtteranceDone(utteranceId: String?) {
        if (_playbackStatus.value != PlaybackStatus.PLAYING) return

        val parsedIndex = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.toIntOrNull()
        val currentIdx = parsedIndex ?: _currentIndex.value
        val list = _sentences.value

        if (currentIdx + 1 < list.size) {
            val nextIdx = currentIdx + 1
            val pauseMs = (_settings.value.sentencePauseMs / _settings.value.speechRate.coerceIn(0.6f, 1.6f))
                .toLong()
                .coerceIn(150L, 1500L)
            scope.launch {
                delay(pauseMs)
                if (_playbackStatus.value == PlaybackStatus.PLAYING) {
                    _currentIndex.value = nextIdx
                    speakSentence(nextIdx)
                }
            }
        } else {
            _playbackStatus.value = PlaybackStatus.COMPLETED
        }
    }

    fun setBookContent(
        newParagraphs: List<ReaderParagraph>,
        newSentences: List<String>,
        startIndex: Int = 0,
        title: String = ""
    ) {
        stop()
        vieneuPreBufferQueue.clearCache()
        _bookTitle.value = title
        _paragraphs.value = newParagraphs
        _sentences.value = newSentences
        _currentIndex.value = startIndex.coerceIn(0, (newSentences.size - 1).coerceAtLeast(0))

        if (isOnDeviceNeuralEngineActive() &&
            isActiveNeuralModelReady() &&
            newSentences.isNotEmpty()
        ) {
            vieneuPreBufferQueue.triggerPrefetch(
                startIndex = _currentIndex.value,
                sentences = newSentences,
                settings = _settings.value
            )
        }
    }

    fun setSentences(newSentences: List<String>, startIndex: Int = 0, title: String = "") {
        setBookContent(emptyList(), newSentences, startIndex, title)
    }

    fun play() {
        val list = _sentences.value
        if (list.isEmpty()) return

        if (_playbackStatus.value == PlaybackStatus.COMPLETED) {
            _currentIndex.value = 0
        }

        if (isOnDeviceNeuralEngineActive() &&
            isActiveNeuralModelReady() &&
            _playbackStatus.value == PlaybackStatus.PAUSED &&
            vieneuAudioPlayer.isPausedMidSentence()
        ) {
            if (vieneuAudioPlayer.resume()) {
                _playbackStatus.value = PlaybackStatus.PLAYING
                return
            }
        }

        _playbackStatus.value = PlaybackStatus.PLAYING
        speakSentence(_currentIndex.value)
    }

    fun pause() {
        if (_playbackStatus.value != PlaybackStatus.PLAYING) return
        _playbackStatus.value = PlaybackStatus.PAUSED
        vieneuAudioPlayer.pause()
        textToSpeech?.stop()
    }

    fun stop() {
        _playbackStatus.value = PlaybackStatus.IDLE
        vieneuSynthesisJob?.cancel()
        vieneuSynthesisJob = null
        vieneuAudioPlayer.stop()
        textToSpeech?.stop()
    }

    fun next() {
        val list = _sentences.value
        if (list.isEmpty()) return
        val nextIdx = (_currentIndex.value + 1).coerceAtMost(list.size - 1)
        seekTo(nextIdx)
    }

    fun previous() {
        val prevIdx = (_currentIndex.value - 1).coerceAtLeast(0)
        seekTo(prevIdx)
    }

    /**
     * Khi người dùng chạm vào một câu bất kỳ trong đoạn văn bản, lập tức chuyển tới câu đó và đọc ngay.
     */
    fun seekAndPlay(index: Int) {
        val list = _sentences.value
        if (list.isEmpty()) return
        val clamped = index.coerceIn(0, list.size - 1)
        _currentIndex.value = clamped

        vieneuSynthesisJob?.cancel()
        vieneuAudioPlayer.stop()
        textToSpeech?.stop()

        _playbackStatus.value = PlaybackStatus.PLAYING
        speakSentence(clamped)
    }

    fun seekTo(index: Int) {
        val list = _sentences.value
        if (list.isEmpty()) return
        val clamped = index.coerceIn(0, list.size - 1)
        _currentIndex.value = clamped

        vieneuSynthesisJob?.cancel()
        vieneuAudioPlayer.stop()
        textToSpeech?.stop()

        if (_playbackStatus.value == PlaybackStatus.PLAYING) {
            speakSentence(clamped)
        } else if (isOnDeviceNeuralEngineActive() && isActiveNeuralModelReady()) {
            vieneuPreBufferQueue.triggerPrefetch(clamped, list, _settings.value)
        }
    }

    private fun speakSentence(index: Int) {
        val list = _sentences.value
        if (index !in list.indices) return

        when (_settings.value.engineType) {
            TtsEngineType.EDGE_ONLINE -> {
                speakWithOnDeviceNeural(index)
            }
            TtsEngineType.VIENEU_ON_DEVICE -> {
                if (vieneuEngine.modelStatus.value == VieNeuModelStatus.READY) {
                    speakWithOnDeviceNeural(index)
                } else {
                    if (vieneuEngine.modelStatus.value == VieNeuModelStatus.NOT_DOWNLOADED) {
                        downloadVieNeuModel()
                    }
                    speakWithNativeTts(index)
                }
            }
            TtsEngineType.PIPER_ON_DEVICE -> {
                if (piperEngine.modelStatus.value == VieNeuModelStatus.READY) {
                    speakWithOnDeviceNeural(index)
                } else {
                    if (piperEngine.modelStatus.value == VieNeuModelStatus.NOT_DOWNLOADED) {
                        downloadPiperModel()
                    }
                    speakWithNativeTts(index)
                }
            }
            TtsEngineType.NATIVE_SYSTEM -> {
                speakWithNativeTts(index)
            }
        }
    }

    private fun speakWithNativeTts(index: Int) {
        val tts = textToSpeech ?: return
        val sentenceToRead = _sentences.value[index]
        val utteranceId = "$UTTERANCE_PREFIX$index"

        warmAudioDsp.setEnabled(_settings.value.warmVoiceFilter)

        // Khi bật bộ lọc Chống chói, giữ headroom âm lượng 0.92f kết hợp với bộ lọc De-Esser phần cứng trên AudioSession
        val volume = if (_settings.value.warmVoiceFilter) 0.92f else 1.0f
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            if (sharedAudioSessionId > 0) {
                putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, sharedAudioSessionId)
            }
        }
        tts.speak(sentenceToRead, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun speakWithOnDeviceNeural(index: Int) {
        vieneuSynthesisJob?.cancel()
        val list = _sentences.value
        if (index !in list.indices) return

        val onSentenceFinished: () -> Unit = {
            scope.launch {
                if (_playbackStatus.value == PlaybackStatus.PLAYING) {
                    if (index + 1 < list.size) {
                        val nextIdx = index + 1
                        _currentIndex.value = nextIdx
                        speakSentence(nextIdx)
                    } else {
                        _playbackStatus.value = PlaybackStatus.COMPLETED
                    }
                }
            }
        }

        // 1. Fast-path (0ms): Nếu câu đã được đệm sẵn trong RAM thì phát ngay lập tức
        val cachedPcm = vieneuPreBufferQueue.getCachedSentence(
            index = index,
            sentences = list,
            settings = _settings.value
        )
        if (cachedPcm != null && cachedPcm.isNotEmpty()) {
            vieneuAudioPlayer.playPcm(
                pcmData = cachedPcm,
                onStart = {
                    scope.launch { _playbackStatus.value = PlaybackStatus.PLAYING }
                },
                onCompleted = onSentenceFinished
            )
            return
        }

        // 2. Clause-Level Streaming
        val clauseChannel = Channel<ShortArray>(Channel.UNLIMITED)
        vieneuAudioPlayer.playStreamingClauses(
            clauseChannel = clauseChannel,
            onStart = {
                scope.launch { _playbackStatus.value = PlaybackStatus.PLAYING }
            },
            onCompleted = onSentenceFinished,
            onEmptyFallback = {
                scope.launch {
                    if (_playbackStatus.value == PlaybackStatus.PLAYING) {
                        speakWithNativeTts(index)
                    }
                }
            }
        )

        vieneuSynthesisJob = scope.launch(Dispatchers.Main) {
            try {
                vieneuPreBufferQueue.streamAndCacheSentence(
                    index = index,
                    sentences = list,
                    settings = _settings.value,
                    onClauseReady = { clausePcm ->
                        if (_playbackStatus.value == PlaybackStatus.PLAYING && clausePcm.isNotEmpty()) {
                            clauseChannel.trySend(clausePcm)
                        }
                    }
                )
            } finally {
                clauseChannel.close()
            }
        }
    }

    fun updateSettings(newSettings: TtsSettings) {
        val oldSettings = _settings.value
        if (oldSettings == newSettings) return

        val wasPlaying = _playbackStatus.value == PlaybackStatus.PLAYING
        _settings.value = newSettings
        ReaderPreferences.saveSettings(context, newSettings)

        warmAudioDsp.setEnabled(newSettings.warmVoiceFilter)
        textToSpeech?.let { applySettingsToNative(it, newSettings) }

        val engineTypeChanged = oldSettings.engineType != newSettings.engineType
        val audioParamsChanged = oldSettings.selectedVieNeuVoice != newSettings.selectedVieNeuVoice ||
                oldSettings.selectedPiperVoice != newSettings.selectedPiperVoice ||
                oldSettings.selectedEdgeVoice != newSettings.selectedEdgeVoice ||
                oldSettings.selectedSystemVoice != newSettings.selectedSystemVoice ||
                oldSettings.speechRate != newSettings.speechRate ||
                oldSettings.pitch != newSettings.pitch ||
                oldSettings.sentencePauseMs != newSettings.sentencePauseMs ||
                oldSettings.clausePauseMs != newSettings.clausePauseMs ||
                oldSettings.warmVoiceFilter != newSettings.warmVoiceFilter

        if (engineTypeChanged) {
            vieneuSynthesisJob?.cancel()
            vieneuAudioPlayer.stop()
            textToSpeech?.stop()
            vieneuPreBufferQueue.clearCache()

            // Giải phóng ngay RAM ONNX của bộ máy không sử dụng (Zero Dual-RAM Overhead)
            when (newSettings.engineType) {
                TtsEngineType.PIPER_ON_DEVICE -> {
                    vieneuEngine.unloadSessions()
                    if (piperEngine.modelStatus.value == VieNeuModelStatus.NOT_DOWNLOADED) {
                        downloadPiperModel()
                    }
                }
                TtsEngineType.VIENEU_ON_DEVICE -> {
                    piperEngine.unloadSessions()
                    if (vieneuEngine.modelStatus.value == VieNeuModelStatus.NOT_DOWNLOADED) {
                        downloadVieNeuModel()
                    }
                }
                TtsEngineType.EDGE_ONLINE,
                TtsEngineType.NATIVE_SYSTEM -> {
                    vieneuEngine.unloadSessions()
                    piperEngine.unloadSessions()
                }
            }

            if (wasPlaying) {
                _playbackStatus.value = PlaybackStatus.PLAYING
                speakSentence(_currentIndex.value)
            } else if (isOnDeviceNeuralEngineActive(newSettings) && isActiveNeuralModelReady(newSettings)) {
                vieneuPreBufferQueue.triggerPrefetch(_currentIndex.value, _sentences.value, newSettings)
            }
            return
        }

        if (isOnDeviceNeuralEngineActive(newSettings)) {
            if (audioParamsChanged) {
                vieneuPreBufferQueue.clearCache()
                if (wasPlaying) {
                    speakSentence(_currentIndex.value)
                } else if (isActiveNeuralModelReady(newSettings)) {
                    vieneuPreBufferQueue.triggerPrefetch(_currentIndex.value, _sentences.value, newSettings)
                }
            } else if (oldSettings.lookaheadBufferSize != newSettings.lookaheadBufferSize) {
                vieneuPreBufferQueue.triggerPrefetch(_currentIndex.value + 1, _sentences.value, newSettings)
            }
        } else {
            if (newSettings.enginePackage != null &&
                newSettings.enginePackage != oldSettings.enginePackage
            ) {
                initializeNativeTts(newSettings.enginePackage)
            } else if (wasPlaying && audioParamsChanged) {
                speakSentence(_currentIndex.value)
            }
        }
    }

    fun resetVoiceSettingsToDefault() {
        val reset = _settings.value.resetVoiceToDefault()
        updateSettings(reset)
    }

    fun downloadVieNeuModel() {
        scope.launch {
            vieneuEngine.downloadModelWeights()
        }
    }

    fun downloadPiperModel() {
        scope.launch {
            piperEngine.downloadModelWeights()
        }
    }

    /**
     * Áp dụng tốc độ, cao độ chuẩn 1.0x và chọn giọng Nam trầm / Nữ dịu của Hệ thống.
     */
    private fun applySettingsToNative(tts: TextToSpeech, settings: TtsSettings) {
        try {
            val allVoices = tts.voices ?: emptySet()
            val targetVoice: Voice? = if (!settings.selectedSystemVoice.isNullOrBlank()) {
                allVoices.find { it.name == settings.selectedSystemVoice }
            } else if (settings.warmVoiceFilter) {
                PREFERRED_WARM_VOICE_KEYWORDS.firstNotNullOfOrNull { keyword ->
                    allVoices.find { v ->
                        v.locale?.language?.startsWith("vi", ignoreCase = true) == true &&
                                v.name?.lowercase()?.contains(keyword) == true
                    }
                }
            } else {
                null
            }

            if (targetVoice != null) {
                tts.voice = targetVoice
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể đổi voice cụ thể của hệ thống: ${e.message}")
        }

        tts.setSpeechRate(settings.speechRate)
        tts.setPitch(settings.pitch.coerceIn(0.5f, 1.5f))
        warmAudioDsp.setEnabled(settings.warmVoiceFilter)
    }

    fun release() {
        vieneuSynthesisJob?.cancel()
        vieneuPreBufferQueue.clearCache()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        warmAudioDsp.release()
        vieneuAudioPlayer.release()
        vieneuEngine.release()
        piperEngine.release()
    }
}
