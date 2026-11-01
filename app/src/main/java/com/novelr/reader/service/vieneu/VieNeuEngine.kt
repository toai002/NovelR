package com.novelr.reader.service.vieneu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.novelr.reader.model.VieNeuModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Engine suy luận giọng đọc AI On-Device thực tế cho **VieNeu-TTS v3 Nano** (`pnnbao-ump/VieNeu-TTS-v3-Nano`).
 *
 * Các tối ưu hóa tốc độ cao cho chip di động 8 nhân (như Xiaomi 15T - Dimensity 8400-Ultra):
 * 1. **Intra-Sentence Clause Streaming**: Tự động chia câu dài thành các mệnh đề ngắn (40–85 ký tự) theo dấu phẩy/chấm phẩy
 *    để phát ngay mệnh đề đầu tiên chỉ sau ~0.5–0.8 giây trong khi các mệnh đề sau được tổng hợp nối tiếp ngầm.
 * 2. **5-Step Sway-Euler ODE + Truncated CFG**: Giảm số lần chạy mạng `vector_estimator.onnx` (148MB) từ 16 lần xuống 8 lần
 *    (chỉ áp dụng Classifier-Free Guidance ở 3 bước định hình đầu tiên và chạy Conditional ở 2 bước tinh chỉnh cuối),
 *    tăng tốc gấp 2 lần mà vẫn giữ nguyên độ tự nhiên và âm sắc.
 * 3. **Tối ưu 6 luồng Big-Core + CPU Arena & Memory Pattern Optimization** trên ONNX Runtime.
 */
class VieNeuEngine(private val context: Context) {

    data class ModelFileSpec(
        val fileName: String,
        val expectedBytes: Long,
        val minValidBytes: Long
    )

    class VoiceEmbedding(
        val id: String,
        val spk: FloatArray,   // [192]
        val style: FloatArray  // [50 * 256 = 12800]
    )

    companion object {
        private const val TAG = "VieNeuEngine"
        private const val MODEL_DIR = "vieneu_v3_nano"
        private const val HF_BASE_URL = "https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Nano/resolve/main"

        private const val CONFIG_ASSET_PATH = "vieneu/config.json"
        private const val VOICES_ASSET_PATH = "vieneu/nano_voices.bin"

        private const val TEXT_ENCODER_FILE = "text_encoder.onnx"
        private const val DURATION_PREDICTOR_FILE = "duration_predictor.onnx"
        private const val VECTOR_ESTIMATOR_FILE = "vector_estimator.onnx"
        private const val CODEC_DECODER_FILE = "codec_decoder.onnx"

        // Ngưỡng ký tự để tách mệnh đề phát streaming tức thì cho câu dài
        private const val STREAM_CLAUSE_TARGET_LEN = 65
        private const val STREAM_CLAUSE_MAX_LEN = 95
        private val CLAUSE_BOUNDARY_REGEX = Regex("(?<=[,;:])\\s+|\\s+(?=[—–-])")

        val MODEL_SPECS = listOf(
            ModelFileSpec(TEXT_ENCODER_FILE, expectedBytes = 25_288_735L, minValidBytes = 20_000_000L),
            ModelFileSpec(DURATION_PREDICTOR_FILE, expectedBytes = 693_971L, minValidBytes = 500_000L),
            ModelFileSpec(VECTOR_ESTIMATOR_FILE, expectedBytes = 147_953_189L, minValidBytes = 120_000_000L),
            ModelFileSpec(CODEC_DECODER_FILE, expectedBytes = 94_724_187L, minValidBytes = 80_000_000L)
        )

        val TOTAL_EXPECTED_BYTES = MODEL_SPECS.sumOf { it.expectedBytes }

        // Ánh xạ tương thích ngược nếu cấu hình cũ còn lưu ID giọng cũ
        private val LEGACY_VOICE_ALIAS = mapOf(
            "hai_dang" to "duc_tri",
            "lan_nhi" to "my_duyen",
            "bao_nam" to "adam",
            "thao_ly" to "ai_han"
        )
    }

    private var ortEnv: OrtEnvironment? = null
    private var textEncoderSession: OrtSession? = null
    private var durationSession: OrtSession? = null
    private var vectorEstimatorSession: OrtSession? = null
    private var codecDecoderSession: OrtSession? = null

    private val g2p = VietnameseG2P(context)

    @Volatile
    private var vocabMap: Map<String, Long>? = null
    private var bosId: Long = 1L
    private var eosId: Long = 2L
    private var padId: Long = 0L

    @Volatile
    private var nullSpk: FloatArray = FloatArray(192)
    @Volatile
    private var nullStyle: FloatArray = FloatArray(50 * 256)
    @Volatile
    private var voiceMap: Map<String, VoiceEmbedding> = emptyMap()

    private val _modelStatus = MutableStateFlow(VieNeuModelStatus.NOT_DOWNLOADED)
    val modelStatus: StateFlow<VieNeuModelStatus> = _modelStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    init {
        loadBundledAssets()
        checkAndInitModels()
    }

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Nạp từ điển cấu hình `config.json` và vector giọng nói `nano_voices.bin` từ `assets/vieneu/`.
     */
    private fun loadBundledAssets() {
        try {
            val jsonStr = context.assets.open(CONFIG_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(jsonStr)
            bosId = root.optLong("bos_id", 1L)
            eosId = root.optLong("eos_id", 2L)
            padId = root.optLong("pad_id", 0L)

            val vocabObj = root.getJSONObject("vocab")
            val map = HashMap<String, Long>(vocabObj.length())
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = vocabObj.getLong(k)
            }
            vocabMap = map

            // Đọc file nhị phân nano_voices.bin (Little-Endian)
            val bytes = context.assets.open(VOICES_ASSET_PATH).use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val voiceCount = buf.int

            val loadedNullSpk = FloatArray(192)
            for (i in 0 until 192) loadedNullSpk[i] = buf.float
            nullSpk = loadedNullSpk

            val loadedNullStyle = FloatArray(50 * 256)
            for (i in 0 until (50 * 256)) loadedNullStyle[i] = buf.float
            nullStyle = loadedNullStyle

            val voices = HashMap<String, VoiceEmbedding>(voiceCount)
            for (v in 0 until voiceCount) {
                val idLen = buf.int
                val idBytes = ByteArray(idLen)
                buf.get(idBytes)
                val voiceId = String(idBytes, Charsets.UTF_8)

                val spk = FloatArray(192)
                for (i in 0 until 192) spk[i] = buf.float

                val style = FloatArray(50 * 256)
                for (i in 0 until (50 * 256)) style[i] = buf.float

                voices[voiceId] = VoiceEmbedding(voiceId, spk, style)
            }
            voiceMap = voices
            Log.i(TAG, "Đã nạp cấu hình VieNeu v3 Nano (${map.size} vocab tokens, ${voices.size} giọng đọc)")
        } catch (e: Exception) {
            Log.e(TAG, "Không thể nạp assets VieNeu: ${e.message}", e)
        }
    }

    fun areAllModelsDownloaded(): Boolean {
        val dir = getModelsDir()
        return MODEL_SPECS.all { spec ->
            val f = File(dir, spec.fileName)
            f.exists() && f.length() >= spec.minValidBytes
        }
    }

    fun checkAndInitModels() {
        if (areAllModelsDownloaded()) {
            loadSessions()
        } else {
            _modelStatus.value = VieNeuModelStatus.NOT_DOWNLOADED
        }
    }

    @Synchronized
    private fun loadSessions() {
        if (textEncoderSession != null && durationSession != null &&
            vectorEstimatorSession != null && codecDecoderSession != null
        ) {
            _modelStatus.value = VieNeuModelStatus.READY
            return
        }

        val dir = getModelsDir()
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            OrtSession.SessionOptions().use { opts ->
                // Tận dụng tối đa tới 6 nhân lớn (All-Big-Core Cortex-A725 trên Xiaomi 15T)
                val cores = Runtime.getRuntime().availableProcessors()
                val threads = (cores - 2).coerceIn(4, 6)
                opts.setIntraOpNumThreads(threads)
                opts.setInterOpNumThreads(1)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.setMemoryPatternOptimization(true)
                opts.setCPUArenaAllocator(true)

                textEncoderSession = env.createSession(File(dir, TEXT_ENCODER_FILE).absolutePath, opts)
                durationSession = env.createSession(File(dir, DURATION_PREDICTOR_FILE).absolutePath, opts)
                vectorEstimatorSession = env.createSession(File(dir, VECTOR_ESTIMATOR_FILE).absolutePath, opts)
                codecDecoderSession = env.createSession(File(dir, CODEC_DECODER_FILE).absolutePath, opts)
            }
            _modelStatus.value = VieNeuModelStatus.READY
            _downloadProgress.value = 1f
            Log.i(TAG, "Đã khởi tạo thành công 4 phiên ONNX của VieNeu-TTS v3 Nano!")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo phiên ONNX VieNeu v3 Nano: ${e.message}", e)
            _modelStatus.value = VieNeuModelStatus.ERROR
        }
    }

    /**
     * Tải thật 4 file mô hình ONNX của `pnnbao-ump/VieNeu-TTS-v3-Nano` (~256 MB) từ HuggingFace.
     */
    suspend fun downloadModelWeights(onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (_modelStatus.value == VieNeuModelStatus.DOWNLOADING) return@withContext
        if (areAllModelsDownloaded()) {
            loadSessions()
            onProgress(1f)
            return@withContext
        }

        _modelStatus.value = VieNeuModelStatus.DOWNLOADING
        _downloadProgress.value = 0f

        val dir = getModelsDir()
        var completedBytes = 0L

        try {
            for (spec in MODEL_SPECS) {
                val targetFile = File(dir, spec.fileName)
                if (targetFile.exists() && targetFile.length() >= spec.minValidBytes) {
                    completedBytes += targetFile.length()
                }
            }
            val initialProgress = (completedBytes.toFloat() / TOTAL_EXPECTED_BYTES).coerceIn(0f, 0.99f)
            _downloadProgress.value = initialProgress
            onProgress(initialProgress)

            for (spec in MODEL_SPECS) {
                val targetFile = File(dir, spec.fileName)
                if (targetFile.exists() && targetFile.length() >= spec.minValidBytes) {
                    continue
                }

                val tempFile = File(dir, "${spec.fileName}.tmp")
                if (tempFile.exists()) tempFile.delete()

                val fileUrl = "$HF_BASE_URL/${spec.fileName}"
                Log.i(TAG, "Đang tải mô hình ONNX: $fileUrl")

                downloadFileWithRedirects(
                    urlStr = fileUrl,
                    destination = tempFile,
                    onBytesRead = { bytesDelta ->
                        completedBytes += bytesDelta
                        val progress = (completedBytes.toFloat() / TOTAL_EXPECTED_BYTES).coerceIn(0.01f, 0.99f)
                        _downloadProgress.value = progress
                        onProgress(progress)
                    }
                )

                if (tempFile.length() < spec.minValidBytes) {
                    tempFile.delete()
                    throw IllegalStateException("File ${spec.fileName} tải về không đầy đủ (${tempFile.length()} bytes)")
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            loadSessions()
            if (_modelStatus.value == VieNeuModelStatus.READY) {
                _downloadProgress.value = 1f
                onProgress(1f)
                Log.i(TAG, "Hoàn tất tải và nạp bộ model VieNeu-TTS v3 Nano!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi tải mô hình VieNeu-TTS từ HuggingFace: ${e.message}", e)
            _modelStatus.value = VieNeuModelStatus.ERROR
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
                setRequestProperty("User-Agent", "NovelR-Android/1.0")
            }

            try {
                val status = conn.responseCode
                if (status in listOf(
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect không có Location header")
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
        throw IllegalStateException("Quá nhiều lần chuyển hướng (redirect) khi tải $urlStr")
    }

    /**
     * Tách một câu dài thành danh sách các mệnh đề (clauses) nhỏ gọn (45–95 ký tự)
     * để mệnh đề đầu tiên có thể phát ra loa chỉ sau ~0.5–0.8 giây, giảm triệt để độ trễ O(T^2) của câu dài.
     */
    internal fun splitIntoStreamingClauses(sentence: String): List<String> {
        val trimmed = sentence.trim()
        if (trimmed.length <= STREAM_CLAUSE_MAX_LEN) {
            return listOf(trimmed)
        }

        val rawParts = trimmed.split(CLAUSE_BOUNDARY_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (rawParts.size <= 1) {
            return splitByWordCount(trimmed, maxChars = STREAM_CLAUSE_MAX_LEN)
        }

        val clauses = ArrayList<String>()
        val current = StringBuilder()

        for (part in rawParts) {
            if (current.isEmpty()) {
                current.append(part)
            } else {
                // Mệnh đề đầu tiên ưu tiên cắt ngắn hơn (~50-75 ký tự) để phát âm thanh ra loa nhanh nhất có thể
                val limit = if (clauses.isEmpty()) STREAM_CLAUSE_TARGET_LEN else STREAM_CLAUSE_MAX_LEN
                if (current.length + 1 + part.length <= limit) {
                    current.append(" ").append(part)
                } else {
                    clauses.add(current.toString())
                    current.clear()
                    current.append(part)
                }
            }
        }
        if (current.isNotEmpty()) {
            clauses.add(current.toString())
        }

        return clauses.flatMap { clause ->
            if (clause.length > STREAM_CLAUSE_MAX_LEN + 25) {
                splitByWordCount(clause, maxChars = STREAM_CLAUSE_MAX_LEN)
            } else {
                listOf(clause)
            }
        }
    }

    private fun splitByWordCount(text: String, maxChars: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isEmpty()) {
                sb.append(w)
            } else if (sb.length + 1 + w.length <= maxChars) {
                sb.append(" ").append(w)
            } else {
                chunks.add("$sb,")
                sb.clear()
                sb.append(w)
            }
        }
        if (sb.isNotEmpty()) {
            chunks.add(sb.toString())
        }
        return chunks
    }

    /**
     * Suy luận Streaming theo từng mệnh đề:
     * Ngay khi mệnh đề đầu tiên của câu dài vừa tính xong (~0.5–0.8s), gọi [onClauseReady] để phát ra loa ngay lập tức,
     * đồng thời tiếp tục tính các mệnh đề còn lại của câu và ghép lại thành mảng PCM hoàn chỉnh để lưu vào RAM cache.
     */
    suspend fun synthesizeStreaming(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
        onClauseReady: (ShortArray) -> Unit
    ): ShortArray = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext ShortArray(0)

        if (_modelStatus.value != VieNeuModelStatus.READY) {
            if (areAllModelsDownloaded()) {
                loadSessions()
            } else {
                return@withContext ShortArray(0)
            }
        }

        if (textEncoderSession == null || durationSession == null ||
            vectorEstimatorSession == null || codecDecoderSession == null || ortEnv == null
        ) {
            return@withContext ShortArray(0)
        }

        val clauses = splitIntoStreamingClauses(text)
        val generatedChunks = ArrayList<ShortArray>(clauses.size)
        var totalSamples = 0

        for (clause in clauses) {
            if (!coroutineContext.isActive) break
            val chunkPcm = try {
                runOnnxInference(clause, voiceId, speed)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi suy luận ONNX mệnh đề '$clause': ${e.message}", e)
                ShortArray(0)
            }
            if (chunkPcm.isNotEmpty()) {
                onClauseReady(chunkPcm)
                generatedChunks.add(chunkPcm)
                totalSamples += chunkPcm.size
            }
        }

        if (generatedChunks.isEmpty()) return@withContext ShortArray(0)
        if (generatedChunks.size == 1) return@withContext generatedChunks[0]

        val combined = ShortArray(totalSamples)
        var offset = 0
        for (chunk in generatedChunks) {
            System.arraycopy(chunk, 0, combined, offset, chunk.size)
            offset += chunk.size
        }
        combined
    }

    /**
     * Suy luận toàn bộ câu (dùng cho tiến trình Prefetch ngầm vào RAM).
     * Vẫn chia theo mệnh đề để tránh độ phức tạp bậc hai O(T^2) của câu dài trong Transformer.
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float
    ): ShortArray = synthesizeStreaming(
        text = text,
        voiceId = voiceId,
        speed = speed,
        pitch = pitch,
        onClauseReady = {}
    )

    /**
     * Pipeline suy luận tốc độ cao của VieNeu-TTS v3 Nano:
     * G2P -> TextEncoder -> DurationPredictor -> 5-step Sway-Euler ODE (Truncated CFG) -> CodecDecoder -> 24kHz PCM
     */
    private fun runOnnxInference(
        text: String,
        voiceId: String,
        speed: Float
    ): ShortArray {
        val env = ortEnv ?: return ShortArray(0)
        val sText = textEncoderSession ?: return ShortArray(0)
        val sDur = durationSession ?: return ShortArray(0)
        val sVe = vectorEstimatorSession ?: return ShortArray(0)
        val sDec = codecDecoderSession ?: return ShortArray(0)

        if (vocabMap == null || voiceMap.isEmpty()) {
            loadBundledAssets()
        }

        // 1. Chuyển văn bản tiếng Việt sang chuỗi âm vị IPA và mã hóa thành chuỗi Token IDs
        val phonemes = g2p.textToPhonemes(text)
        val ids = phonemesToTokenIds(phonemes)
        val seqLen = ids.size
        if (seqLen < 4) return ShortArray(0)

        // 2. Lấy vector đặc trưng giọng đọc (Speaker [192] + Style [50, 256])
        val resolvedVoiceId = LEGACY_VOICE_ALIAS[voiceId] ?: voiceId
        val voice = voiceMap[resolvedVoiceId]
            ?: voiceMap["duc_tri"]
            ?: voiceMap.values.firstOrNull()
            ?: return ShortArray(0)

        // Tối ưu Sway-Euler: 5 bước ODE + Truncated CFG ở 3 bước đầu (tổng 8 NFE thay vì 16 NFE -> Nhanh gấp 2x)
        val steps = 5
        val cfgActiveSteps = 3
        val cfgScale = 2.5f
        val swayCoef = -1.0

        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, seqLen.toLong())).use { idsTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.style), longArrayOf(1L, 50L, 256L)).use { styleTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.spk), longArrayOf(1L, 192L)).use { spkTensor ->

                    // Bước 1: Text Encoder -> ctx [1, L, 256]
                    val ctxFlat = sText.run(mapOf("ids" to idsTensor, "style" to styleTensor)).use { textOut ->
                        extractFloatArray(textOut[0] as OnnxTensor)
                    }

                    OnnxTensor.createTensor(env, FloatBuffer.wrap(ctxFlat), longArrayOf(1L, seqLen.toLong(), 256L)).use { ctxTensor ->
                        val maskArray = arrayOf(BooleanArray(seqLen) { true })
                        OnnxTensor.createTensor(env, maskArray).use { ctxMaskTensor ->

                            // Bước 2: Duration Predictor -> T frames
                            val logSeconds = sDur.run(
                                mapOf("ctx" to ctxTensor, "ctx_mask" to ctxMaskTensor, "spk" to spkTensor)
                            ).use { durOut ->
                                extractFloatArray(durOut[0] as OnnxTensor).firstOrNull() ?: 0f
                            }

                            val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
                            val durationSec = (exp(logSeconds.toDouble()) / clampedSpeed).coerceIn(0.25, 12.0)
                            val latentFrames = (durationSec * VieNeuConstants.FLOW_FPS).roundToInt().coerceAtLeast(2)

                            // Bước 3: Khởi tạo nhiễu Gaussian x [1, 144, T] và lưới thời gian Sway-Euler
                            val latentSize = 144 * latentFrames
                            val rng = Random(42L)
                            val x = FloatArray(latentSize) { rng.nextGaussian().toFloat() }

                            val timeGrid = DoubleArray(steps + 1) { idx ->
                                val u = idx.toDouble() / steps.toDouble()
                                u + swayCoef * (cos(PI * 0.5 * u) - 1.0 + u)
                            }

                            val nullCtxFlat = FloatArray(seqLen * 256)
                            val nullMaskArray = arrayOf(BooleanArray(seqLen) { false })

                            OnnxTensor.createTensor(env, FloatBuffer.wrap(nullCtxFlat), longArrayOf(1L, seqLen.toLong(), 256L)).use { nullCtxTensor ->
                                OnnxTensor.createTensor(env, nullMaskArray).use { nullMaskTensor ->
                                    OnnxTensor.createTensor(env, FloatBuffer.wrap(nullSpk), longArrayOf(1L, 192L)).use { nullSpkTensor ->
                                        OnnxTensor.createTensor(env, FloatBuffer.wrap(nullStyle), longArrayOf(1L, 50L, 256L)).use { nullStyleTensor ->

                                            // Giải ODE 5 bước với Truncated CFG trên Vector Estimator
                                            for (step in 0 until steps) {
                                                val tVal = floatArrayOf(timeGrid[step].toFloat())
                                                val dt = (timeGrid[step + 1] - timeGrid[step]).toFloat()

                                                OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1L, 144L, latentFrames.toLong())).use { xTensor ->
                                                    OnnxTensor.createTensor(env, FloatBuffer.wrap(tVal), longArrayOf(1L)).use { tTensor ->
                                                        val vCond = sVe.run(
                                                            mapOf(
                                                                "x" to xTensor,
                                                                "t" to tTensor,
                                                                "ctx" to ctxTensor,
                                                                "ctx_mask" to ctxMaskTensor,
                                                                "spk" to spkTensor,
                                                                "style" to styleTensor
                                                            )
                                                        ).use { out -> extractFloatArray(out[0] as OnnxTensor) }

                                                        if (step < cfgActiveSteps) {
                                                            val vUncond = sVe.run(
                                                                mapOf(
                                                                    "x" to xTensor,
                                                                    "t" to tTensor,
                                                                    "ctx" to nullCtxTensor,
                                                                    "ctx_mask" to nullMaskTensor,
                                                                    "spk" to nullSpkTensor,
                                                                    "style" to nullStyleTensor
                                                                )
                                                            ).use { out -> extractFloatArray(out[0] as OnnxTensor) }

                                                            for (idx in 0 until latentSize) {
                                                                val vu = vUncond[idx]
                                                                val guidedV = vu + cfgScale * (vCond[idx] - vu)
                                                                x[idx] += dt * guidedV
                                                            }
                                                        } else {
                                                            for (idx in 0 until latentSize) {
                                                                x[idx] += dt * vCond[idx]
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Bước 4: Giải mã bằng NeuCodec Decoder -> Waveform 24kHz
                            val rawWav = OnnxTensor.createTensor(
                                env,
                                FloatBuffer.wrap(x),
                                longArrayOf(1L, 144L, latentFrames.toLong())
                            ).use { finalXTensor ->
                                sDec.run(mapOf("x" to finalXTensor)).use { decOut ->
                                    extractFloatArray(decOut[0] as OnnxTensor)
                                }
                            }

                            return postProcessWaveform(rawWav)
                        }
                    }
                }
            }
        }
    }

    private fun extractFloatArray(tensor: OnnxTensor): FloatArray {
        val fb = tensor.floatBuffer
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    /**
     * Cắt khoảng lặng thừa đầu/cuối và làm mượt biên độ (Fade-in 15ms / Fade-out 25ms).
     */
    private fun postProcessWaveform(wav: FloatArray): ShortArray {
        if (wav.isEmpty()) return ShortArray(0)
        val sr = VieNeuConstants.SAMPLE_RATE
        val threshold = 0.006f

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
            startIdx = (firstActive - (0.04f * sr).toInt()).coerceAtLeast(0)
            endIdx = (lastActive + (0.06f * sr).toInt()).coerceAtMost(wav.size)
        } else {
            startIdx = 0
            endIdx = wav.size
        }

        val length = (endIdx - startIdx).coerceAtLeast(0)
        if (length == 0) return ShortArray(0)

        val fadeInSamples = (0.015f * sr).toInt().coerceAtMost(length)
        val fadeOutSamples = (0.025f * sr).toInt().coerceAtMost(length)

        val pcm = ShortArray(length)
        for (i in 0 until length) {
            var sample = wav[startIdx + i]
            if (i < fadeInSamples && fadeInSamples > 0) {
                sample *= (i.toFloat() / fadeInSamples.toFloat())
            }
            if (i >= length - fadeOutSamples && fadeOutSamples > 0) {
                val remain = length - 1 - i
                sample *= (remain.toFloat() / fadeOutSamples.toFloat())
            }
            val shortVal = (sample * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[i] = shortVal.toShort()
        }
        return pcm
    }

    /**
     * Mã hóa chuỗi âm vị IPA (bao gồm thẻ `<|emotion_k|>`) thành mảng `LongArray` cho `text_encoder.onnx`.
     */
    internal fun phonemesToTokenIds(phonemes: String): LongArray {
        val vocab = vocabMap ?: emptyMap()
        val ids = ArrayList<Long>(phonemes.length + 4)
        ids.add(bosId)

        var i = 0
        while (i < phonemes.length) {
            if (phonemes.startsWith("<|emotion_", startIndex = i)) {
                val closeIdx = phonemes.indexOf("|>", startIndex = i)
                if (closeIdx != -1) {
                    val tag = phonemes.substring(i, closeIdx + 2)
                    vocab[tag]?.let { ids.add(it) }
                    i = closeIdx + 2
                    continue
                }
            }
            val ch = phonemes[i].toString()
            vocab[ch]?.let { ids.add(it) }
            i++
        }

        while (ids.size < 3) {
            ids.add(padId)
        }
        ids.add(eosId)
        return ids.toLongArray()
    }

    fun release() {
        try {
            textEncoderSession?.close()
            durationSession?.close()
            vectorEstimatorSession?.close()
            codecDecoderSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải phóng phiên ONNX: ${e.message}")
        } finally {
            textEncoderSession = null
            durationSession = null
            vectorEstimatorSession = null
            codecDecoderSession = null
            ortEnv = null
        }
    }
}
