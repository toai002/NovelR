package com.novelr.reader.service.piper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.WarmAudioDspProcessor
import com.novelr.reader.service.vieneu.VietnameseG2P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Công cụ tổng hợp giọng nói Offline tốc độ cao **Piper VITS Multi-Voice**:
 * - Hỗ trợ 8 giọng đọc HD rõ chữ chuẩn dấu (dựa trên `vi_VN-vais1000-medium.onnx` 22.05kHz)
 *   cùng 65 giọng đọc thực tế đa dạng vùng miền (dựa trên `vi_VN-vivos-x_low.onnx` 16kHz).
 * - Suy luận 1 bước (Single-Pass VITS Forward), tốc độ phản hồi ~80–140ms/câu trên Xiaomi 15T.
 * - Tự động giải phóng RAM khi chuyển sang VieNeu AI hoặc Giọng Hệ Thống.
 */
class PiperEngine(private val context: Context) {

    data class ModelFileSpec(
        val fileName: String,
        val url: String,
        val expectedBytes: Long
    )

    companion object {
        private const val TAG = "PiperEngine"

        val MODEL_SPECS = listOf(
            ModelFileSpec(
                fileName = "vi_VN-vais1000-medium.onnx",
                url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/vi/vi_VN/vais1000/medium/vi_VN-vais1000-medium.onnx",
                expectedBytes = 63_201_294L
            ),
            ModelFileSpec(
                fileName = "vi_VN-vivos-x_low.onnx",
                url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/vi/vi_VN/vivos/x_low/vi_VN-vivos-x_low.onnx",
                expectedBytes = 27_789_413L
            )
        )

        val TOTAL_EXPECTED_BYTES: Long = MODEL_SPECS.sumOf { it.expectedBytes }
    }

    private val modelDir = File(context.filesDir, "piper_models")
    private val g2p = VietnameseG2P(context)

    private var ortEnv: OrtEnvironment? = null
    private var vaisSession: OrtSession? = null
    private var vivosSession: OrtSession? = null

    private var vaisPhonemeMap: Map<String, IntArray>? = null
    private var vivosPhonemeMap: Map<String, IntArray>? = null

    private val _modelStatus = MutableStateFlow(VieNeuModelStatus.NOT_DOWNLOADED)
    val modelStatus: StateFlow<VieNeuModelStatus> = _modelStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    init {
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        loadPhonemeMaps()
        if (areAllModelsDownloaded()) {
            _modelStatus.value = VieNeuModelStatus.READY
            _downloadProgress.value = 1f
        }
    }

    fun areAllModelsDownloaded(): Boolean = MODEL_SPECS.all { spec ->
        val f = File(modelDir, spec.fileName)
        f.exists() && f.length() >= (spec.expectedBytes * 0.95).toLong()
    }

    @Synchronized
    private fun loadPhonemeMaps() {
        if (vaisPhonemeMap != null && vivosPhonemeMap != null) return
        vaisPhonemeMap = parsePhonemeIdMap("piper/vi_VN-vais1000-medium.onnx.json")
        vivosPhonemeMap = parsePhonemeIdMap("piper/vi_VN-vivos-x_low.onnx.json")
    }

    private fun parsePhonemeIdMap(assetPath: String): Map<String, IntArray> {
        val result = HashMap<String, IntArray>(180)
        try {
            val jsonStr = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(jsonStr)
            val idMapObj = root.getJSONObject("phoneme_id_map")
            val keys = idMapObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = idMapObj.getJSONArray(key)
                val ids = IntArray(arr.length()) { idx -> arr.getInt(idx) }
                result[key] = ids
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc $assetPath: ${e.message}", e)
        }
        return result
    }

    @Synchronized
    private fun ensureSessionLoaded(useVivos: Boolean): OrtSession? {
        if (!areAllModelsDownloaded()) return null
        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }

        if (useVivos) {
            vivosSession?.let { return it }
        } else {
            vaisSession?.let { return it }
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (cores - 2).coerceIn(2, 6)

        return try {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val fileName = if (useVivos) "vi_VN-vivos-x_low.onnx" else "vi_VN-vais1000-medium.onnx"
            val session = env.createSession(File(modelDir, fileName).absolutePath, opts)
            if (useVivos) {
                vivosSession = session
            } else {
                vaisSession = session
            }
            _modelStatus.value = VieNeuModelStatus.READY
            session
        } catch (e: Exception) {
            Log.e(TAG, "Không thể nạp phiên ONNX Piper: ${e.message}", e)
            null
        }
    }

    /**
     * Giải phóng phiên ONNX khỏi RAM khi người dùng chuyển sang công cụ khác (như VieNeu AI)
     * để điện thoại luôn nhẹ và không tốn RAM kép.
     */
    @Synchronized
    fun unloadSessions() {
        try {
            vaisSession?.close()
        } catch (_: Exception) {}
        try {
            vivosSession?.close()
        } catch (_: Exception) {}
        vaisSession = null
        vivosSession = null
    }

    suspend fun downloadModelWeights(): Boolean = withContext(Dispatchers.IO) {
        if (_modelStatus.value == VieNeuModelStatus.DOWNLOADING) return@withContext false

        try {
            _modelStatus.value = VieNeuModelStatus.DOWNLOADING
            _downloadProgress.value = 0.01f

            var completedBytes = 0L
            for (spec in MODEL_SPECS) {
                val targetFile = File(modelDir, spec.fileName)
                if (targetFile.exists() && targetFile.length() >= (spec.expectedBytes * 0.95).toLong()) {
                    completedBytes += spec.expectedBytes
                    _downloadProgress.value = (completedBytes.toFloat() / TOTAL_EXPECTED_BYTES).coerceIn(0.01f, 0.99f)
                    continue
                }

                val tempFile = File(modelDir, "${spec.fileName}.tmp")
                if (tempFile.exists()) tempFile.delete()

                downloadFileWithRedirects(spec.url, tempFile) { bytesRead ->
                    completedBytes += bytesRead
                    val progress = (completedBytes.toFloat() / TOTAL_EXPECTED_BYTES).coerceIn(0.01f, 0.99f)
                    _downloadProgress.value = progress
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            _downloadProgress.value = 1f
            _modelStatus.value = VieNeuModelStatus.READY
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tải bộ model Piper VITS: ${e.message}", e)
            _modelStatus.value = VieNeuModelStatus.ERROR
            false
        }
    }

    private fun downloadFileWithRedirects(
        urlStr: String,
        destination: File,
        onBytesRead: (Int) -> Unit
    ) {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 8) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NovelR-Android/2.1")
            }
            try {
                val status = conn.responseCode
                if (status in listOf(
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect thiếu Location header")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $status khi tải $currentUrl")
                }

                BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            onBytesRead(read)
                        }
                        output.flush()
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Quá nhiều redirect khi tải $urlStr")
    }

    /**
     * Tổng hợp câu thành mảng PCM 24kHz siêu tốc (~80–140ms/câu).
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
        sentencePauseMs: Int = 550,
        clausePauseMs: Int = 200,
        warmVoiceFilter: Boolean = true
    ): ShortArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext ShortArray(0)
        loadPhonemeMaps()

        val profile = PiperConstants.getVoiceProfile(voiceId)
        val session = ensureSessionLoaded(profile.isVivosMultiSpeaker) ?: return@withContext ShortArray(0)
        val env = ortEnv ?: return@withContext ShortArray(0)

        val phonemeMap = if (profile.isVivosMultiSpeaker) vivosPhonemeMap else vaisPhonemeMap
        if (phonemeMap.isNullOrEmpty()) return@withContext ShortArray(0)

        // 1. Chuyển văn bản sang chuỗi IPA và ánh xạ sang dãy ID có chèn pad (0) xen kẽ chuẩn Piper VITS
        val ipa = g2p.textToPhonemes(text)
        val tokenIds = phonemesToPiperIds(ipa, phonemeMap)
        if (tokenIds.size < 4) return@withContext ShortArray(0)

        val userSpeed = speed.coerceIn(0.5f, 2.0f)
        val userPitch = pitch.coerceIn(0.6f, 1.4f)

        // Kết hợp hệ số formant của nhân vật và cao độ mà vẫn giữ nguyên tốc độ đọc thực tế
        val effectiveRateRatio = (profile.formantShift * userPitch).coerceIn(0.65f, 1.35f)
        val lengthScale = (effectiveRateRatio / userSpeed).coerceIn(0.35f, 2.2f)
        val scalesArr = floatArrayOf(profile.noiseScale, lengthScale, profile.noiseW)
        val lenArr = longArrayOf(tokenIds.size.toLong())

        val rawWav: FloatArray = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokenIds),
            longArrayOf(1L, tokenIds.size.toLong())
        ).use { inputTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(lenArr), longArrayOf(1L)).use { lenTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(scalesArr), longArrayOf(3L)).use { scalesTensor ->
                    if (profile.isVivosMultiSpeaker) {
                        val sidArr = longArrayOf(profile.speakerId.toLong())
                        OnnxTensor.createTensor(env, LongBuffer.wrap(sidArr), longArrayOf(1L)).use { sidTensor ->
                            session.run(
                                mapOf(
                                    "input" to inputTensor,
                                    "input_lengths" to lenTensor,
                                    "scales" to scalesTensor,
                                    "sid" to sidTensor
                                )
                            ).use { out ->
                                extractFloatArray(out[0] as OnnxTensor)
                            }
                        }
                    } else {
                        session.run(
                            mapOf(
                                "input" to inputTensor,
                                "input_lengths" to lenTensor,
                                "scales" to scalesTensor
                            )
                        ).use { out ->
                            extractFloatArray(out[0] as OnnxTensor)
                        }
                    }
                }
            }
        }

        if (rawWav.isEmpty()) return@withContext ShortArray(0)

        // 2. Nội suy Hermite bậc 3 về tần số chuẩn 24.000 Hz của bộ phát âm thanh
        val nativeSampleRate = if (profile.isVivosMultiSpeaker) 16000f else 22050f
        val virtualSourceRate = nativeSampleRate * effectiveRateRatio
        val resampled24k = resampleHermite(rawWav, virtualSourceRate, PiperConstants.OUTPUT_SAMPLE_RATE.toFloat())

        // 3. Cắt khoảng lặng thừa, làm mượt biên độ đầu/cuối và áp dụng bộ lọc chống chói DSP
        val pauseMs = (sentencePauseMs / userSpeed).roundToInt().coerceIn(180, 1500)
        postProcessWaveform24k(resampled24k, pauseMs, warmVoiceFilter)
    }

    private fun phonemesToPiperIds(ipa: String, idMap: Map<String, IntArray>): LongArray {
        val list = ArrayList<Long>(ipa.length * 2 + 4)
        list.add(1L) // '^' BOS
        list.add(0L) // '_' PAD

        for (ch in ipa) {
            val mapped = idMap[ch.toString()]
            if (mapped != null) {
                for (id in mapped) {
                    list.add(id.toLong())
                    list.add(0L)
                }
            }
        }
        list.add(2L) // '$' EOS
        return list.toLongArray()
    }

    private fun extractFloatArray(tensor: OnnxTensor): FloatArray {
        val fb = tensor.floatBuffer
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    /**
     * Nội suy đa thức bậc 3 Hermite (4-point Cubic Hermite Interpolation) giúp tái lấy mẫu sang 24kHz
     * giữ nguyên độ nét của phụ âm, không gây nhiễu răng cưa.
     */
    private fun resampleHermite(input: FloatArray, srcRate: Float, dstRate: Float): FloatArray {
        if (input.size < 4) return input
        val ratio = srcRate / dstRate
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val maxIdx = input.size - 1

        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = pos - idx

            val y0 = input[(idx - 1).coerceIn(0, maxIdx)]
            val y1 = input[idx.coerceIn(0, maxIdx)]
            val y2 = input[(idx + 1).coerceIn(0, maxIdx)]
            val y3 = input[(idx + 2).coerceIn(0, maxIdx)]

            val c0 = y1
            val c1 = 0.5f * (y2 - y0)
            val c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3
            val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)

            out[i] = ((c3 * frac + c2) * frac + c1) * frac + c0
        }
        return out
    }

    private fun postProcessWaveform24k(
        wav: FloatArray,
        trailingPauseMs: Int,
        warmVoiceFilter: Boolean
    ): ShortArray {
        val sr = PiperConstants.OUTPUT_SAMPLE_RATE
        val threshold = 0.005f

        var firstActive = -1
        var lastActive = -1
        for (i in wav.indices) {
            if (abs(wav[i]) > threshold) {
                if (firstActive == -1) firstActive = i
                lastActive = i
            }
        }

        val startIdx: Int
        val endIdx: Int
        if (firstActive != -1 && lastActive >= firstActive) {
            startIdx = (firstActive - (0.045f * sr).toInt()).coerceAtLeast(0)
            endIdx = (lastActive + (0.075f * sr).toInt()).coerceAtMost(wav.size)
        } else {
            startIdx = 0
            endIdx = wav.size
        }

        val voiceLength = (endIdx - startIdx).coerceAtLeast(0)
        if (voiceLength == 0) return ShortArray(0)

        val fadeInSamples = (0.022f * sr).toInt().coerceAtMost(voiceLength)
        val fadeOutSamples = (0.045f * sr).toInt().coerceAtMost(voiceLength)
        val pauseSamples = ((trailingPauseMs / 1000f) * sr).toInt().coerceAtLeast(0)
        val leadInSamples = (0.015f * sr).toInt()
        val totalLength = leadInSamples + voiceLength + pauseSamples

        return WarmAudioDspProcessor.processPcm24kHz(
            wav = wav,
            startIdx = startIdx,
            voiceLength = voiceLength,
            fadeInSamples = fadeInSamples,
            fadeOutSamples = fadeOutSamples,
            leadInSamples = leadInSamples,
            totalLength = totalLength,
            warmEnabled = warmVoiceFilter
        )
    }

    /**
     * Tổng hợp câu hỗ trợ callback phát mệnh đề theo luồng (Streaming).
     * Với Piper VITS, tốc độ suy luận nguyên câu chỉ ~80–120ms nên ta tổng hợp nguyên câu
     * và gọi callback `onClauseReady` ngay lập tức để đồng bộ kiến trúc với VieNeu AI.
     */
    suspend fun synthesizeStreaming(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
        sentencePauseMs: Int = 550,
        clausePauseMs: Int = 200,
        warmVoiceFilter: Boolean = true,
        onClauseReady: (ShortArray) -> Unit
    ): ShortArray {
        val pcm = synthesize(
            text = text,
            voiceId = voiceId,
            speed = speed,
            pitch = pitch,
            sentencePauseMs = sentencePauseMs,
            clausePauseMs = clausePauseMs,
            warmVoiceFilter = warmVoiceFilter
        )
        if (pcm.isNotEmpty()) {
            onClauseReady(pcm)
        }
        return pcm
    }

    fun release() {
        unloadSessions()
        ortEnv = null
    }
}
