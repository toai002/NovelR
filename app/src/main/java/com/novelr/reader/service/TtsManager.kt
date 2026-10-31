package com.novelr.reader.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.novelr.reader.model.EngineInfo
import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.service.vieneu.VieNeuAudioPlayer
import com.novelr.reader.service.vieneu.VieNeuEngine
import com.novelr.reader.service.vieneu.VieNeuPreBufferQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    }

    // --- Native TTS Engine ---
    private var textToSpeech: TextToSpeech? = null

    // --- VieNeu AI On-Device Engine ---
    val vieneuEngine = VieNeuEngine(context)
    private val vieneuAudioPlayer = VieNeuAudioPlayer(scope)
    val vieneuPreBufferQueue = VieNeuPreBufferQueue(vieneuEngine, scope)

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.IDLE)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _sentences = MutableStateFlow<List<String>>(emptyList())
    val sentences: StateFlow<List<String>> = _sentences.asStateFlow()

    private val _availableEngines = MutableStateFlow<List<EngineInfo>>(emptyList())
    val availableEngines: StateFlow<List<EngineInfo>> = _availableEngines.asStateFlow()

    private val _settings = MutableStateFlow(TtsSettings())
    val settings: StateFlow<TtsSettings> = _settings.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val vietnameseLocale = Locale("vi", "VN")
    private var vieneuSynthesisJob: Job? = null

    init {
        initializeNativeTts(enginePackage = null)
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
            val warn = "Thiếu gói ngôn ngữ Tiếng Việt (vi-VN) trên máy. Bạn có thể chuyển sang VieNeu AI On-Device."
            Log.w(TAG, warn)
            _errorMessage.value = warn
            tts.language = Locale.getDefault()
        } else {
            _errorMessage.value = null
        }

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

    private fun handleUtteranceDone(utteranceId: String?) {
        if (_playbackStatus.value != PlaybackStatus.PLAYING) return

        val parsedIndex = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.toIntOrNull()
        val currentIdx = parsedIndex ?: _currentIndex.value
        val list = _sentences.value

        if (currentIdx + 1 < list.size) {
            val nextIdx = currentIdx + 1
            _currentIndex.value = nextIdx
            speakSentence(nextIdx)
        } else {
            _playbackStatus.value = PlaybackStatus.COMPLETED
        }
    }

    fun setSentences(newSentences: List<String>, startIndex: Int = 0) {
        stop()
        vieneuPreBufferQueue.clearCache()
        _sentences.value = newSentences
        _currentIndex.value = startIndex.coerceIn(0, (newSentences.size - 1).coerceAtLeast(0))

        if (_settings.value.engineType == TtsEngineType.VIENEU_ON_DEVICE && newSentences.isNotEmpty()) {
            vieneuPreBufferQueue.triggerPrefetch(
                startIndex = _currentIndex.value,
                sentences = newSentences,
                settings = _settings.value
            )
        }
    }

    fun play() {
        val list = _sentences.value
        if (list.isEmpty()) return

        if (_playbackStatus.value == PlaybackStatus.COMPLETED) {
            _currentIndex.value = 0
        }

        // Nếu đang tạm dừng giữa câu ở chế độ VieNeu AI, tiếp tục phát mượt mà tại đúng vị trí dừng
        if (_settings.value.engineType == TtsEngineType.VIENEU_ON_DEVICE &&
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
        if (_settings.value.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
            vieneuAudioPlayer.pause()
        } else {
            textToSpeech?.stop()
        }
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
        } else if (_settings.value.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
            vieneuPreBufferQueue.triggerPrefetch(clamped, list, _settings.value)
        }
    }

    private fun speakSentence(index: Int) {
        val list = _sentences.value
        if (index !in list.indices) return

        if (_settings.value.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
            speakWithVieNeu(index)
        } else {
            speakWithNativeTts(index)
        }
    }

    private fun speakWithNativeTts(index: Int) {
        val tts = textToSpeech ?: return
        val sentenceToRead = _sentences.value[index]
        val utteranceId = "$UTTERANCE_PREFIX$index"

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts.speak(sentenceToRead, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun speakWithVieNeu(index: Int) {
        vieneuSynthesisJob?.cancel()
        vieneuSynthesisJob = scope.launch(Dispatchers.Main) {
            val list = _sentences.value
            val pcm = vieneuPreBufferQueue.getOrSynthesizeSentence(
                index = index,
                sentences = list,
                settings = _settings.value
            )

            if (_playbackStatus.value != PlaybackStatus.PLAYING) return@launch

            vieneuAudioPlayer.playPcm(
                pcmData = pcm,
                onStart = {
                    scope.launch { _playbackStatus.value = PlaybackStatus.PLAYING }
                },
                onCompleted = {
                    scope.launch {
                        if (_playbackStatus.value == PlaybackStatus.PLAYING) {
                            if (index + 1 < list.size) {
                                val nextIdx = index + 1
                                _currentIndex.value = nextIdx
                                speakWithVieNeu(nextIdx)
                            } else {
                                _playbackStatus.value = PlaybackStatus.COMPLETED
                            }
                        }
                    }
                }
            )
        }
    }

    fun updateSettings(newSettings: TtsSettings) {
        val oldSettings = _settings.value
        if (oldSettings == newSettings) return

        val wasPlaying = _playbackStatus.value == PlaybackStatus.PLAYING
        _settings.value = newSettings

        textToSpeech?.let { applySettingsToNative(it, newSettings) }

        val engineTypeChanged = oldSettings.engineType != newSettings.engineType
        val audioParamsChanged = oldSettings.selectedVieNeuVoice != newSettings.selectedVieNeuVoice ||
                oldSettings.speechRate != newSettings.speechRate ||
                oldSettings.pitch != newSettings.pitch

        if (engineTypeChanged) {
            vieneuSynthesisJob?.cancel()
            vieneuAudioPlayer.stop()
            textToSpeech?.stop()
            if (wasPlaying) {
                _playbackStatus.value = PlaybackStatus.PLAYING
                speakSentence(_currentIndex.value)
            } else if (newSettings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
                vieneuPreBufferQueue.triggerPrefetch(_currentIndex.value, _sentences.value, newSettings)
            }
            return
        }

        if (newSettings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
            if (audioParamsChanged) {
                vieneuPreBufferQueue.clearCache()
                if (wasPlaying) {
                    speakSentence(_currentIndex.value)
                } else {
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

    fun downloadVieNeuModel() {
        scope.launch {
            vieneuEngine.downloadModelWeights()
        }
    }

    private fun applySettingsToNative(tts: TextToSpeech, settings: TtsSettings) {
        tts.setSpeechRate(settings.speechRate)
        tts.setPitch(settings.pitch)
    }

    fun release() {
        vieneuSynthesisJob?.cancel()
        vieneuPreBufferQueue.clearCache()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        vieneuAudioPlayer.release()
        vieneuEngine.release()
    }
}
