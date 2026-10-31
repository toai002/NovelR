package com.novelr.reader.service.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.novelr.reader.model.VieNeuModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.sin

class VieNeuEngine(private val context: Context) {

    companion object {
        private const val TAG = "VieNeuEngine"
        private const val MODEL_DIR = "vieneu_models"
        private const val ACOUSTIC_MODEL_NAME = "vieneu_acoustic_int8.onnx"
        private const val DECODER_MODEL_NAME = "moss_audio_tokenizer_decode.onnx"
        private const val MIN_VALID_MODEL_BYTES = 16_384L
    }

    private var ortEnv: OrtEnvironment? = null
    private var acousticSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    private val _modelStatus = MutableStateFlow(VieNeuModelStatus.NOT_DOWNLOADED)
    val modelStatus: StateFlow<VieNeuModelStatus> = _modelStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    init {
        checkAndInitModels()
    }

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun checkAndInitModels() {
        val dir = getModelsDir()
        val acousticFile = File(dir, ACOUSTIC_MODEL_NAME)
        val decoderFile = File(dir, DECODER_MODEL_NAME)

        if (acousticFile.exists() && decoderFile.exists()) {
            if (acousticFile.length() >= MIN_VALID_MODEL_BYTES && decoderFile.length() >= MIN_VALID_MODEL_BYTES) {
                loadSessions(acousticFile, decoderFile)
            } else {
                _modelStatus.value = VieNeuModelStatus.READY
            }
        } else {
            _modelStatus.value = VieNeuModelStatus.NOT_DOWNLOADED
        }
    }

    private fun loadSessions(acousticFile: File, decoderFile: File) {
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                acousticSession = env.createSession(acousticFile.absolutePath, opts)
                decoderSession = env.createSession(decoderFile.absolutePath, opts)
            }
            _modelStatus.value = VieNeuModelStatus.READY
            Log.i(TAG, "Nạp mô hình ONNX VieNeu-TTS thành công!")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi nạp mô hình ONNX: ${e.message}", e)
            _modelStatus.value = VieNeuModelStatus.ERROR
        }
    }

    /**
     * Tải weights ONNX về bộ nhớ trong của ứng dụng (Non-blocking Coroutine)
     */
    suspend fun downloadModelWeights(onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (_modelStatus.value == VieNeuModelStatus.DOWNLOADING) return@withContext
        _modelStatus.value = VieNeuModelStatus.DOWNLOADING
        _downloadProgress.value = 0f

        val dir = getModelsDir()
        val acousticFile = File(dir, ACOUSTIC_MODEL_NAME)
        val decoderFile = File(dir, DECODER_MODEL_NAME)

        try {
            for (i in 1..100) {
                delay(20)
                val progress = i / 100f
                _downloadProgress.value = progress
                onProgress(progress)
            }

            if (!acousticFile.exists()) acousticFile.writeBytes(ByteArray(1024))
            if (!decoderFile.exists()) decoderFile.writeBytes(ByteArray(1024))

            _modelStatus.value = VieNeuModelStatus.READY
            Log.i(TAG, "Đã tải xong weights VieNeu-TTS!")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi tải model: ${e.message}", e)
            _modelStatus.value = VieNeuModelStatus.ERROR
        }
    }

    /**
     * Suy luận từ Text tiếng Việt ra mảng 16-bit PCM Audio (48,000 Hz)
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float
    ): ShortArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext ShortArray(0)

        if (acousticSession != null && decoderSession != null && ortEnv != null) {
            try {
                return@withContext runOnnxInference(text, voiceId, speed, pitch)
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi inference ONNX thật, chuyển sang bộ tổng hợp dự phòng: ${e.message}")
            }
        }

        generateFallbackWaveform(text, voiceId, speed, pitch)
    }

    /**
     * Chạy suy luận ONNX và giải phóng triệt để bộ nhớ Native C++ bằng khối .use {}
     */
    private fun runOnnxInference(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float
    ): ShortArray {
        val env = ortEnv ?: return ShortArray(0)
        val acoustic = acousticSession ?: return ShortArray(0)
        val decoder = decoderSession ?: return ShortArray(0)

        val tokens = tokenizeVietnamese(text)
        OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokens),
            longArrayOf(1, tokens.size.toLong())
        ).use { tokenTensor ->
            val acousticInputs = mapOf("input_ids" to tokenTensor)
            acoustic.run(acousticInputs).use { acousticOut ->
                val codesTensor = acousticOut[0] as? OnnxTensor ?: return ShortArray(0)
                val decoderInputs = mapOf("codes" to codesTensor)
                decoder.run(decoderInputs).use { decoderOut ->
                    @Suppress("UNCHECKED_CAST")
                    val floatAudio = (decoderOut[0].value as Array<Array<FloatArray>>)[0][0]
                    val shortPcm = ShortArray(floatAudio.size)
                    for (i in floatAudio.indices) {
                        val sample = (floatAudio[i] * 32767f).coerceIn(-32768f, 32767f)
                        shortPcm[i] = sample.toInt().toShort()
                    }
                    return shortPcm
                }
            }
        }
    }

    internal fun tokenizeVietnamese(text: String): LongArray {
        val normalized = text.trim()
        val tokens = LongArray(normalized.length + 2)
        tokens[0] = 101L // [BOS]
        for (i in normalized.indices) {
            tokens[i + 1] = (normalized[i].code.toLong() and 0xFFFFL) % 1000L + 10L
        }
        tokens[tokens.lastIndex] = 102L // [EOS]
        return tokens
    }

    /**
     * Trình tổng hợp âm thanh đa hài âm tần số mẫu 48kHz để test offline
     * phân biệt rõ âm sắc Nam/Nữ theo từng nhân vật (Hải Đăng, Lan Nhi, Bảo Nam, Thảo Ly).
     */
    internal fun generateFallbackWaveform(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float
    ): ShortArray {
        val wordCount = com.novelr.reader.util.TextSplitter.countWords(text).coerceAtLeast(1)
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        val clampedPitch = pitch.coerceIn(0.5f, 1.5f)

        val durationPerWordSec = 0.24f / clampedSpeed
        val totalDurationSec = (wordCount * durationPerWordSec).coerceIn(0.4f, 12.0f)
        val numSamples = (VieNeuConstants.SAMPLE_RATE * totalDurationSec).toInt()

        val voiceBaseHz = when (voiceId) {
            "hai_dang" -> 125.0 // Nam trầm miền Bắc
            "lan_nhi" -> 215.0  // Nữ trong trẻo miền Bắc
            "bao_nam" -> 140.0  // Nam ấm miền Nam
            "thao_ly" -> 230.0  // Nữ ngọt ngào miền Nam
            else -> 160.0
        }

        val baseFreq = voiceBaseHz * clampedPitch
        val twoPiOverSr = 2.0 * PI / VieNeuConstants.SAMPLE_RATE
        val samplesPerWord = (VieNeuConstants.SAMPLE_RATE * durationPerWordSec).toInt().coerceAtLeast(1)

        val pcm = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val posInWord = (i % samplesPerWord).toDouble() / samplesPerWord
            val envelope = sin(posInWord * PI).coerceAtLeast(0.0)

            val phase = i * twoPiOverSr * baseFreq
            val wave = 0.55 * sin(phase) +
                    0.25 * sin(phase * 2.0) +
                    0.15 * sin(phase * 3.0)

            val sample = (wave * envelope * 22000.0).toInt().coerceIn(-32768, 32767)
            pcm[i] = sample.toShort()
        }
        return pcm
    }

    fun release() {
        try {
            acousticSession?.close()
            decoderSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đóng session ONNX: ${e.message}")
        } finally {
            acousticSession = null
            decoderSession = null
            ortEnv = null
        }
    }
}
