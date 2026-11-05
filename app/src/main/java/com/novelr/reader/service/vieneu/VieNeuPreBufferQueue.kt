package com.novelr.reader.service.vieneu

import android.util.Log
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.piper.PiperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Hàng đợi đệm trước (Pre-Buffering Lookahead Queue) kết hợp Streaming theo mệnh đề
 * dùng chung cho cả **Piper VITS Multi-Voice** và **VieNeu-TTS**.
 *
 * Tối ưu hóa:
 * - **Fast-path 0ms**: Nếu câu đã được render trước trong RAM, trả về ngay lập tức.
 * - **Clause-Level Streaming**: Nếu câu dài chưa có trong cache, vừa phát mệnh đề đầu tiên
 *   vừa tiếp tục tổng hợp các mệnh đề còn lại và lưu toàn bộ câu vào RAM.
 * - **Priority Preemption**: Hủy ngay các tác vụ prefetch ngầm nếu người dùng nhảy câu (Seek).
 */
class VieNeuPreBufferQueue(
    private val vieneuEngine: VieNeuEngine,
    private val piperEngine: PiperEngine,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VieNeuPreBufferQueue"
    }

    private val audioCache = ConcurrentHashMap<Int, ShortArray>()

    private val _prebufferedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val prebufferedIndices: StateFlow<Set<Int>> = _prebufferedIndices.asStateFlow()

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    private var currentPrefetchJob: Job? = null
    private val synthesisMutex = Mutex()

    /**
     * Kiểm tra và lấy ngay câu đã được đệm sẵn trong RAM (độ trễ 0ms).
     */
    fun getCachedSentence(
        index: Int,
        sentences: List<String>,
        settings: TtsSettings
    ): ShortArray? {
        val cached = audioCache[index] ?: return null
        val lookahead = settings.lookaheadBufferSize.coerceIn(1, 4)
        evictOutsideWindow(index, lookahead)
        triggerPrefetch(index + 1, sentences, settings)
        return cached
    }

    /**
     * Suy luận câu theo từng mệnh đề (Clause-Level Streaming) để phát ngay mệnh đề đầu tiên,
     * sau đó lưu toàn bộ câu vào RAM cache và kích hoạt prefetch cho các câu kế tiếp.
     */
    suspend fun streamAndCacheSentence(
        index: Int,
        sentences: List<String>,
        settings: TtsSettings,
        onClauseReady: (ShortArray) -> Unit
    ): ShortArray {
        val lookahead = settings.lookaheadBufferSize.coerceIn(1, 4)

        audioCache[index]?.let { cached ->
            onClauseReady(cached)
            evictOutsideWindow(index, lookahead)
            triggerPrefetch(index + 1, sentences, settings)
            return cached
        }

        // Hủy job prefetch ngầm để nhường toàn bộ CPU cho mệnh đề đầu tiên của câu đang cần phát
        currentPrefetchJob?.cancel()

        _isSynthesizing.value = true
        val pcm = try {
            synthesisMutex.withLock {
                audioCache[index]?.also { cached ->
                    onClauseReady(cached)
                } ?: run {
                    val text = sentences.getOrNull(index) ?: return@run ShortArray(0)
                    val synthesized = if (settings.engineType == TtsEngineType.PIPER_ON_DEVICE) {
                        piperEngine.synthesizeStreaming(
                            text = text,
                            voiceId = settings.selectedPiperVoice,
                            speed = settings.speechRate,
                            pitch = settings.pitch,
                            sentencePauseMs = settings.sentencePauseMs,
                            clausePauseMs = settings.clausePauseMs,
                            warmVoiceFilter = settings.warmVoiceFilter,
                            onClauseReady = onClauseReady
                        )
                    } else {
                        vieneuEngine.synthesizeStreaming(
                            text = text,
                            voiceId = settings.selectedVieNeuVoice,
                            speed = settings.speechRate,
                            pitch = settings.pitch,
                            sentencePauseMs = settings.sentencePauseMs,
                            clausePauseMs = settings.clausePauseMs,
                            warmVoiceFilter = settings.warmVoiceFilter,
                            onClauseReady = onClauseReady
                        )
                    }
                    if (synthesized.isNotEmpty()) {
                        audioCache[index] = synthesized
                        updateBufferedIndices()
                    }
                    synthesized
                }
            }
        } finally {
            _isSynthesizing.value = false
        }

        evictOutsideWindow(index, lookahead)
        triggerPrefetch(index + 1, sentences, settings)
        return pcm
    }

    suspend fun getOrSynthesizeSentence(
        index: Int,
        sentences: List<String>,
        settings: TtsSettings
    ): ShortArray = streamAndCacheSentence(
        index = index,
        sentences = sentences,
        settings = settings,
        onClauseReady = {}
    )

    /**
     * Kích hoạt tiến trình chạy ngầm để render trước N câu tiếp theo vào RAM
     */
    fun triggerPrefetch(
        startIndex: Int,
        sentences: List<String>,
        settings: TtsSettings
    ) {
        currentPrefetchJob?.cancel()
        if (sentences.isEmpty() || startIndex >= sentences.size) return

        val isEngineReady = when (settings.engineType) {
            TtsEngineType.PIPER_ON_DEVICE -> piperEngine.modelStatus.value == VieNeuModelStatus.READY
            TtsEngineType.VIENEU_ON_DEVICE -> vieneuEngine.modelStatus.value == VieNeuModelStatus.READY
            TtsEngineType.NATIVE_SYSTEM -> false
        }
        if (!isEngineReady) return

        currentPrefetchJob = scope.launch(Dispatchers.Default) {
            val lookahead = settings.lookaheadBufferSize.coerceIn(1, 4)
            val endIndex = (startIndex + lookahead).coerceAtMost(sentences.size)

            for (idx in startIndex until endIndex) {
                if (!isActive) break
                if (audioCache.containsKey(idx)) continue

                val sentence = sentences.getOrNull(idx) ?: continue
                try {
                    _isSynthesizing.value = true
                    val pcm = synthesisMutex.withLock {
                        audioCache[idx] ?: if (settings.engineType == TtsEngineType.PIPER_ON_DEVICE) {
                            piperEngine.synthesize(
                                text = sentence,
                                voiceId = settings.selectedPiperVoice,
                                speed = settings.speechRate,
                                pitch = settings.pitch,
                                sentencePauseMs = settings.sentencePauseMs,
                                clausePauseMs = settings.clausePauseMs,
                                warmVoiceFilter = settings.warmVoiceFilter
                            )
                        } else {
                            vieneuEngine.synthesize(
                                text = sentence,
                                voiceId = settings.selectedVieNeuVoice,
                                speed = settings.speechRate,
                                pitch = settings.pitch,
                                sentencePauseMs = settings.sentencePauseMs,
                                clausePauseMs = settings.clausePauseMs,
                                warmVoiceFilter = settings.warmVoiceFilter
                            )
                        }
                    }
                    if (isActive && pcm.isNotEmpty()) {
                        audioCache[idx] = pcm
                        updateBufferedIndices()
                        Log.d(TAG, "Đã render trước xong câu $idx (${pcm.size} mẫu)")
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi khi prefetch câu $idx: ${e.message}")
                } finally {
                    _isSynthesizing.value = false
                }
            }
        }
    }

    private fun evictOutsideWindow(currentIndex: Int, lookahead: Int) {
        val minKeep = (currentIndex - 1).coerceAtLeast(0)
        val maxKeep = currentIndex + lookahead + 1

        var modified = false
        val iterator = audioCache.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key < minKeep || key > maxKeep) {
                iterator.remove()
                modified = true
            }
        }
        if (modified) {
            updateBufferedIndices()
        }
    }

    fun clearCache() {
        currentPrefetchJob?.cancel()
        currentPrefetchJob = null
        audioCache.clear()
        _isSynthesizing.value = false
        updateBufferedIndices()
    }

    private fun updateBufferedIndices() {
        _prebufferedIndices.value = audioCache.keys.toSet()
    }
}
