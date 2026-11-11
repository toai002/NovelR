package com.novelr.reader.service.vieneu

import android.util.Log
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.edgetts.EdgeTtsEngine
import com.novelr.reader.service.piper.PiperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Hàng đợi đệm trước (Pre-Buffering Lookahead Queue) kết hợp Streaming theo mệnh đề
 * dùng chung cho cả **Microsoft Edge TTS (Online)**, **Piper VITS Multi-Voice** và **VieNeu-TTS**.
 *
 * Tối ưu hóa đặc biệt cho **Edge TTS Online (v2.2.1)**:
 * - **Tăng khoảng đệm thông minh (8–12 câu)**: Tự động mở rộng cửa sổ đệm trước lên tối thiểu 8 câu
 *   (tối đa 12 câu) cho Edge TTS để đọc liền mạch không bao giờ bị khựng giữa chừng.
 * - **Tải ngầm đa luồng song song (3x Parallel Prefetch)**: Tải đồng thời 3 câu tiếp theo cùng lúc
 *   trên 3 kết nối WebSocket ấm thay vì chờ từng câu tuần tự.
 * - **Tái sử dụng tác vụ đang tải (In-Flight Deduplication)**: Nếu câu tiếp theo đang được tải ngầm dở dang (vd: đã tải 80%),
 *   hệ thống sẽ chờ nối tiếp kết quả đó thay vì hủy đi tải lại từ đầu.
 * - **Kích hoạt Prefetch ngay khi bắt đầu phát câu đầu**: Vừa phát mệnh đề đầu của câu hiện tại vừa tải luôn
 *   các câu kế tiếp ở nền.
 */
class VieNeuPreBufferQueue(
    private val vieneuEngine: VieNeuEngine,
    private val piperEngine: PiperEngine,
    private val edgeTtsEngine: EdgeTtsEngine,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VieNeuPreBufferQueue"
        private const val EDGE_PARALLEL_PREFETCH_CONCURRENCY = 3
        private const val MIN_EDGE_LOOKAHEAD = 8
        private const val MAX_LOOKAHEAD = 12
    }

    private val audioCache = ConcurrentHashMap<Int, ShortArray>()
    private val inFlightEdgeJobs = ConcurrentHashMap<Int, Deferred<ShortArray>>()
    private val edgePrefetchSemaphore = Semaphore(EDGE_PARALLEL_PREFETCH_CONCURRENCY)

    private val _prebufferedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val prebufferedIndices: StateFlow<Set<Int>> = _prebufferedIndices.asStateFlow()

    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()

    private var currentPrefetchJob: Job? = null
    private val onDeviceCpuMutex = Mutex()

    /**
     * Tính toán số câu đệm trước thực tế:
     * - Với Edge TTS Online: luôn đệm trước tối thiểu 8 câu (hoặc đến 12 câu theo cài đặt) vì dữ liệu PCM trong RAM rất nhẹ.
     * - Với Piper / VieNeu Offline: đệm theo cài đặt người dùng (1..12 câu).
     */
    private fun resolveEffectiveLookahead(settings: TtsSettings): Int {
        return if (settings.engineType == TtsEngineType.EDGE_ONLINE) {
            maxOf(settings.lookaheadBufferSize, MIN_EDGE_LOOKAHEAD).coerceIn(MIN_EDGE_LOOKAHEAD, MAX_LOOKAHEAD)
        } else {
            settings.lookaheadBufferSize.coerceIn(1, MAX_LOOKAHEAD)
        }
    }

    /**
     * Kiểm tra và lấy ngay câu đã được đệm sẵn trong RAM (độ trễ 0ms).
     */
    fun getCachedSentence(
        index: Int,
        sentences: List<String>,
        settings: TtsSettings
    ): ShortArray? {
        val cached = audioCache[index] ?: return null
        val lookahead = resolveEffectiveLookahead(settings)
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
        val lookahead = resolveEffectiveLookahead(settings)

        // 1. Nếu câu đã có sẵn trong RAM cache -> phát ngay lập tức 0ms
        audioCache[index]?.let { cached ->
            onClauseReady(cached)
            evictOutsideWindow(index, lookahead)
            triggerPrefetch(index + 1, sentences, settings)
            return cached
        }

        // 2. Đường chạy siêu tốc riêng cho Microsoft Edge TTS (Online Network I/O)
        if (settings.engineType == TtsEngineType.EDGE_ONLINE) {
            evictOutsideWindow(index, lookahead)
            // Kích hoạt tải ngầm ngay lập tức các câu tiếp theo (index + 1 .. index + lookahead) song song!
            triggerPrefetch(index + 1, sentences, settings)

            // Nếu câu hiện tại đang được tải ngầm dở dang trong inFlightEdgeJobs -> tận dụng luôn kết quả đó!
            inFlightEdgeJobs[index]?.let { existingDeferred ->
                if (existingDeferred.isActive) {
                    _isSynthesizing.value = true
                    try {
                        val awaited = existingDeferred.await()
                        if (awaited.isNotEmpty()) {
                            audioCache[index] = awaited
                            updateBufferedIndices()
                            onClauseReady(awaited)
                            return awaited
                        }
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (_: Exception) {
                        // Nếu job ngầm lỗi thì chuyển xuống synthesizeStreaming trực tiếp
                    } finally {
                        _isSynthesizing.value = inFlightEdgeJobs.isNotEmpty()
                    }
                }
            }

            val text = sentences.getOrNull(index) ?: return ShortArray(0)
            _isSynthesizing.value = true
            return try {
                val synthesized = edgeTtsEngine.synthesizeStreaming(
                    text = text,
                    voiceId = settings.selectedEdgeVoice,
                    speed = settings.speechRate,
                    pitch = settings.pitch,
                    sentencePauseMs = settings.sentencePauseMs,
                    clausePauseMs = settings.clausePauseMs,
                    warmVoiceFilter = settings.warmVoiceFilter,
                    onClauseReady = onClauseReady
                )
                if (synthesized.isNotEmpty()) {
                    audioCache[index] = synthesized
                    updateBufferedIndices()
                }
                synthesized
            } finally {
                _isSynthesizing.value = inFlightEdgeJobs.isNotEmpty()
            }
        }

        // 3. Đường chạy cho bộ máy ONNX Offline trên CPU (Piper VITS & VieNeu AI)
        currentPrefetchJob?.cancel()

        _isSynthesizing.value = true
        val pcm = try {
            onDeviceCpuMutex.withLock {
                audioCache[index]?.also { cached ->
                    onClauseReady(cached)
                } ?: run {
                    val text = sentences.getOrNull(index) ?: return@run ShortArray(0)
                    val synthesized = when (settings.engineType) {
                        TtsEngineType.PIPER_ON_DEVICE -> {
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
                        }
                        else -> {
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
     * Kích hoạt tiến trình chạy ngầm để render trước N câu tiếp theo vào RAM.
     */
    fun triggerPrefetch(
        startIndex: Int,
        sentences: List<String>,
        settings: TtsSettings
    ) {
        if (sentences.isEmpty() || startIndex >= sentences.size) return

        val isEngineReady = when (settings.engineType) {
            TtsEngineType.EDGE_ONLINE -> true
            TtsEngineType.PIPER_ON_DEVICE -> piperEngine.modelStatus.value == VieNeuModelStatus.READY
            TtsEngineType.VIENEU_ON_DEVICE -> vieneuEngine.modelStatus.value == VieNeuModelStatus.READY
            TtsEngineType.NATIVE_SYSTEM -> false
        }
        if (!isEngineReady) return

        val lookahead = resolveEffectiveLookahead(settings)
        val endIndex = (startIndex + lookahead).coerceAtMost(sentences.size)

        if (settings.engineType == TtsEngineType.EDGE_ONLINE) {
            // Làm nóng trước hồ kết nối WebSocket của Edge TTS
            edgeTtsEngine.warmUpPool(settings.selectedEdgeVoice)

            // Phóng các worker tải song song (tối đa 3 câu cùng lúc) mà không hủy các câu đang tải dở trong cửa sổ
            for (idx in startIndex until endIndex) {
                if (audioCache.containsKey(idx)) continue
                val existing = inFlightEdgeJobs[idx]
                if (existing != null && existing.isActive) continue

                val sentence = sentences.getOrNull(idx) ?: continue
                val deferred = scope.async(Dispatchers.IO) {
                    edgePrefetchSemaphore.withPermit {
                        if (!isActive) return@withPermit ShortArray(0)
                        audioCache[idx]?.let { return@withPermit it }

                        _isSynthesizing.value = true
                        try {
                            val pcm = edgeTtsEngine.synthesize(
                                text = sentence,
                                voiceId = settings.selectedEdgeVoice,
                                speed = settings.speechRate,
                                pitch = settings.pitch,
                                sentencePauseMs = settings.sentencePauseMs,
                                clausePauseMs = settings.clausePauseMs,
                                warmVoiceFilter = settings.warmVoiceFilter
                            )
                            if (isActive && pcm.isNotEmpty()) {
                                audioCache[idx] = pcm
                                updateBufferedIndices()
                                Log.d(TAG, "Đã tải trước xong câu Edge TTS #$idx (${pcm.size} mẫu)")
                            }
                            pcm
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Lỗi khi prefetch Edge câu $idx: ${e.message}")
                            ShortArray(0)
                        } finally {
                            inFlightEdgeJobs.remove(idx)
                            _isSynthesizing.value = inFlightEdgeJobs.isNotEmpty()
                        }
                    }
                }
                inFlightEdgeJobs[idx] = deferred
            }
            return
        }

        // Prefetch tuần tự cho ONNX Offline (Piper / VieNeu)
        currentPrefetchJob?.cancel()
        currentPrefetchJob = scope.launch(Dispatchers.Default) {
            for (idx in startIndex until endIndex) {
                if (!isActive) break
                if (audioCache.containsKey(idx)) continue

                val sentence = sentences.getOrNull(idx) ?: continue
                try {
                    _isSynthesizing.value = true
                    val pcm = onDeviceCpuMutex.withLock {
                        audioCache[idx] ?: when (settings.engineType) {
                            TtsEngineType.PIPER_ON_DEVICE -> {
                                piperEngine.synthesize(
                                    text = sentence,
                                    voiceId = settings.selectedPiperVoice,
                                    speed = settings.speechRate,
                                    pitch = settings.pitch,
                                    sentencePauseMs = settings.sentencePauseMs,
                                    clausePauseMs = settings.clausePauseMs,
                                    warmVoiceFilter = settings.warmVoiceFilter
                                )
                            }
                            else -> {
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
        val minKeep = (currentIndex - 2).coerceAtLeast(0)
        val maxKeep = currentIndex + lookahead + 2

        // Hủy các job Edge TTS nằm ngoài cửa sổ đọc hiện tại (ví dụ khi người dùng tua xa)
        val jobIter = inFlightEdgeJobs.entries.iterator()
        while (jobIter.hasNext()) {
            val entry = jobIter.next()
            if (entry.key < minKeep || entry.key > maxKeep) {
                entry.value.cancel()
                jobIter.remove()
            }
        }

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
        inFlightEdgeJobs.values.forEach { it.cancel() }
        inFlightEdgeJobs.clear()
        audioCache.clear()
        _isSynthesizing.value = false
        updateBufferedIndices()
    }

    private fun updateBufferedIndices() {
        _prebufferedIndices.value = audioCache.keys.toSet()
    }
}
