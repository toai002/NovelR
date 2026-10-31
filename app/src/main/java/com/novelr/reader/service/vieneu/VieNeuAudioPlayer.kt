package com.novelr.reader.service.vieneu

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VieNeuAudioPlayer(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VieNeuAudioPlayer"
        private const val CHUNK_SIZE = 2048
    }

    private val sampleRate = VieNeuConstants.SAMPLE_RATE
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(4096)

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var playbackJob: Job? = null
    private val isPaused = AtomicBoolean(false)
    private val isActiveSession = AtomicBoolean(false)
    private val sessionIdGenerator = AtomicLong(0L)

    init {
        ensureAudioTrack()
    }

    @Synchronized
    private fun ensureAudioTrack(): AudioTrack? {
        val current = audioTrack
        if (current != null && current.state == AudioTrack.STATE_INITIALIZED) {
            return current
        }
        try {
            current?.release()
        } catch (_: Exception) {}

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { audioTrack = it }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tạo AudioTrack: ${e.message}", e)
            null
        }
    }

    fun isPausedMidSentence(): Boolean = isActiveSession.get() && isPaused.get()

    /**
     * Phát một mảng PCM Audio cho một câu.
     * Khi phát xong hoàn toàn, gọi callback [onCompleted].
     * Sử dụng [sessionIdGenerator] để tránh race-condition khi chuyển câu liên tục.
     */
    fun playPcm(
        pcmData: ShortArray,
        onStart: () -> Unit,
        onCompleted: () -> Unit
    ) {
        stop()
        if (pcmData.isEmpty()) {
            onCompleted()
            return
        }

        val currentSessionId = sessionIdGenerator.incrementAndGet()
        isActiveSession.set(true)
        isPaused.set(false)

        playbackJob = scope.launch(Dispatchers.IO) {
            val track = ensureAudioTrack() ?: return@launch
            try {
                track.play()
                onStart()

                var offset = 0
                while (offset < pcmData.size && isActive && sessionIdGenerator.get() == currentSessionId) {
                    if (isPaused.get()) {
                        track.pause()
                        while (isPaused.get() && isActive && sessionIdGenerator.get() == currentSessionId) {
                            delay(25)
                        }
                        if (isActive && sessionIdGenerator.get() == currentSessionId) {
                            track.play()
                        } else {
                            break
                        }
                    }

                    val toWrite = minOf(CHUNK_SIZE, pcmData.size - offset)
                    val written = track.write(pcmData, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        offset += written
                    } else {
                        break
                    }
                }

                if (isActive && sessionIdGenerator.get() == currentSessionId && !isPaused.get() && offset >= pcmData.size) {
                    // Chờ phần cứng xả nốt buffer cuối cùng
                    val drainMs = (minBufferSize.toFloat() / (sampleRate * 2) * 1000).toLong()
                    delay(drainMs.coerceIn(40L, 180L))
                    if (isActive && sessionIdGenerator.get() == currentSessionId && !isPaused.get()) {
                        isActiveSession.set(false)
                        onCompleted()
                    }
                }
            } catch (_: CancellationException) {
                // Hủy chủ động khi seek hoặc stop
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi trong khi phát AudioTrack: ${e.message}", e)
            } finally {
                if (sessionIdGenerator.get() == currentSessionId) {
                    isActiveSession.set(false)
                }
            }
        }
    }

    fun pause() {
        if (!isActiveSession.get()) return
        isPaused.set(true)
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi pause: ${e.message}")
        }
    }

    fun resume(): Boolean {
        if (!isActiveSession.get() || !isPaused.get()) return false
        isPaused.set(false)
        return true
    }

    fun stop() {
        sessionIdGenerator.incrementAndGet()
        isActiveSession.set(false)
        isPaused.set(false)
        playbackJob?.cancel()
        playbackJob = null
        try {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                track.pause()
                track.flush()
                track.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi stop AudioTrack: ${e.message}")
        }
    }

    fun release() {
        stop()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải phóng AudioTrack: ${e.message}")
        }
    }
}
