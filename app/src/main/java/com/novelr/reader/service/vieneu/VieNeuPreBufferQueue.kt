package com.novelr.reader.service.vieneu

import android.util.Log
import com.novelr.reader.model.TtsSettings
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
 * Hàng đợi đệm trước (Pre-Buffering Lookahead Queue) cho VieNeu-TTS.
 *
 * Tối ưu hóa:
 * - Ưu tiên luồng chính (Priority Preemption): Hủy ngay các tác vụ prefetch ngầm nếu người dùng nhảy câu (Seek) tới câu chưa có trong cache.
 * - Quản lý cửa sổ trượt RAM (Sliding Window Eviction): Giữ tối đa [currentIndex - 1 .. currentIndex + lookahead + 1] câu trong RAM.
 */
class VieNeuPreBufferQueue(
    private val engine: VieNeuEngine,
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
     * Lấy audio của câu mục tiêu. Nếu đã có trong RAM cache thì trả về tức thì (0ms latency).
     * Nếu chưa có, hủy prefetch cũ để ưu tiên tổng hợp câu hiện tại ngay lập tức.
     */
    suspend fun getOrSynthesizeSentence(
        index: Int,
        sentences: List<String>,
        settings: TtsSettings
    ): ShortArray {
        val lookahead = settings.lookaheadBufferSize.coerceIn(1, 4)

        // 1. Fast-path: Đã có sẵn trong RAM cache
        audioCache[index]?.let { cached ->
            evictOutsideWindow(index, lookahead)
            triggerPrefetch(index + 1, sentences, settings)
            return cached
        }

        // 2. Hủy job prefetch ngầm để nhường CPU/Mutex cho câu hiện tại
        currentPrefetchJob?.cancel()

        _isSynthesizing.value = true
        val pcm = try {
            synthesisMutex.withLock {
                audioCache[index] ?: run {
                    val text = sentences.getOrNull(index) ?: return@run ShortArray(0)
                    val synthesized = engine.synthesize(
                        text = text,
                        voiceId = settings.selectedVieNeuVoice,
                        speed = settings.speechRate,
                        pitch = settings.pitch
                    )
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

        // 3. Dọn dẹp cửa sổ trượt và kích hoạt prefetch cho các câu kế tiếp
        evictOutsideWindow(index, lookahead)
        triggerPrefetch(index + 1, sentences, settings)
        return pcm
    }

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
                        audioCache[idx] ?: engine.synthesize(
                            text = sentence,
                            voiceId = settings.selectedVieNeuVoice,
                            speed = settings.speechRate,
                            pitch = settings.pitch
                        )
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

    /**
     * Giữ lại các câu trong cửa sổ [currentIndex - 1 .. currentIndex + lookahead + 1],
     * giải phóng toàn bộ các câu ở xa để tránh rò rỉ RAM khi đọc sách dài hoặc nhảy trang.
     */
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
