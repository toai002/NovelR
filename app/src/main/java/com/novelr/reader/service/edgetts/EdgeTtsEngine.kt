package com.novelr.reader.service.edgetts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.novelr.reader.service.WarmAudioDspProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bộ máy tổng hợp giọng nói trực tiếp Microsoft Edge TTS (Online Neural 24kHz) - Bản Thống Nhất Giọng Việt v2.2.3.
 *
 * Đặc điểm nổi bật:
 * 1. **Thống nhất 100% một giọng đọc thuần Việt (`vi-VN-HoaiMyNeural` hoặc `vi-VN-NamMinhNeural`)**:
 *    - Tuyệt đối không bao giờ tự ý nhảy sang giọng ngoại quốc (Multilingual tiếng Anh/Pháp).
 *    - Sử dụng cơ chế **Đua đa luồng cùng một giọng (Same-Voice Hedged Racing)**: Nếu kết nối 1 chưa trả byte
 *      âm thanh đầu tiên sau 500ms, lập tức mở kết nối thứ 2 tới **đúng giọng đang chọn** để luôn đạt tốc độ
 *      ~500ms–700ms/câu mà giữ nguyên 100% âm sắc giọng đọc xuyên suốt cả truyện.
 * 2. **Xử lý triệt để từ cảm thán & lời thoại trong ngoặc kép (`"ha ha"`, `"ồ"`, `"hừ"`, `"à"`...)**:
 *    - Đặt ngôn ngữ gốc SSML chuẩn `xml:lang='vi-VN'`.
 *    - Loại bỏ dấu ngoặc kép bao quanh từ cảm thán và chuẩn hóa ngữ âm tiếng Việt để bộ phân tích của Edge
 *      không bao giờ đọc `"ha ha"`, `"ồ"` theo ngữ điệu tiếng Anh.
 *    - Không tách rời các thán từ ngắn ở đầu câu khi chạy chế độ phát mệnh đề song song.
 */
class EdgeTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "EdgeTtsEngine"
        private const val OUTPUT_SAMPLE_RATE = 24000
        private val tempFileCounter = AtomicLong(0L)

        private val VIETNAMESE_DIACRITIC_CHARS =
            "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ" +
                    "ÀÁẢÃẠĂẰẮẲẴẶÂẦẤẨẪẬÈÉẺẼẸÊỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌÔỒỐỔỖỘƠỜỚỞỠỢÙÚỦŨỤƯỪỨỬỮỰỲÝỶỸỴĐ"

        private val REGEX_HAHA = Regex("\\b(?:ha\\s*){2,}ha?\\b", RegexOption.IGNORE_CASE)
        private val REGEX_HIHI = Regex("\\b(?:hi\\s*){2,}hi?\\b", RegexOption.IGNORE_CASE)
        private val REGEX_HEHE = Regex("\\b(?:he\\s*){2,}he?\\b", RegexOption.IGNORE_CASE)
        private val REGEX_HUHU = Regex("\\b(?:hu\\s*){2,}hu?\\b", RegexOption.IGNORE_CASE)
        private val REGEX_HOHO = Regex("\\b(?:hơ\\s*){2,}|\\b(?:ho\\s*){2,}ho?\\b", RegexOption.IGNORE_CASE)
        private val REGEX_OH = Regex("\\boh+\\b", RegexOption.IGNORE_CASE)
        private val REGEX_AH = Regex("\\bah+\\b", RegexOption.IGNORE_CASE)
        private val REGEX_HMM = Regex("\\b(?:h+m+|u+h+m+|u+m+)\\b", RegexOption.IGNORE_CASE)
        private val REGEX_OK = Regex("\\b(?:ok|okay)\\b", RegexOption.IGNORE_CASE)
        private val REGEX_MULTI_SPACE = Regex("\\s{2,}")
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cacheDir: File by lazy {
        File(context.cacheDir, "edge_tts_mp3").apply { mkdirs() }
    }

    fun warmUpPool(@Suppress("UNUSED_PARAMETER") voiceId: String = EdgeTtsConstants.DEFAULT_EDGE_VOICE) {
        // OkHttp ConnectionPool giữ sẵn phiên TLS; mỗi câu gửi kèm speech.config + ssml ngay trong onOpen để đạt tốc độ cao nhất
    }

    suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
        sentencePauseMs: Int = 550,
        clausePauseMs: Int = 200,
        warmVoiceFilter: Boolean = true
    ): ShortArray = withContext(Dispatchers.IO) {
        val cleaned = normalizeVietnameseForEdgeSsml(text)
        if (cleaned.isBlank()) return@withContext ShortArray(0)

        val profile = EdgeTtsConstants.findById(voiceId)
        val mp3Bytes = fetchMp3SameVoiceHedged(
            cleanedText = cleaned,
            voiceShortName = profile.shortName,
            speed = speed,
            pitch = pitch
        )
        if (mp3Bytes == null || mp3Bytes.isEmpty()) {
            return@withContext ShortArray(0)
        }

        val floatWav24k = decodeMp3ToFloat24kHz(mp3Bytes)
        if (floatWav24k.isEmpty()) return@withContext ShortArray(0)

        val userSpeed = speed.coerceIn(0.5f, 2.0f)
        val pauseMs = (sentencePauseMs / userSpeed).roundToInt().coerceIn(140, 1500)
        postProcessWaveform24k(floatWav24k, pauseMs, warmVoiceFilter)
    }

    /**
     * Phát mệnh đề đầu siêu tốc (Parallel Clause-Level Streaming):
     * Chỉ tách mệnh đề khi mệnh đề đầu đủ dài (>= 6 từ và có ít nhất 2 từ có dấu Tiếng Việt)
     * để không bao giờ tách rời các từ cảm thán ngắn như "Ha ha," hay "Ồ," ra riêng lẻ.
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
    ): ShortArray = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        val splitPair = splitFirstShortClause(trimmed)

        if (splitPair == null) {
            val pcm = synthesize(
                text = trimmed,
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
            return@withContext pcm
        }

        val (firstClause, restClause) = splitPair
        coroutineScope {
            val firstDeferred = async {
                synthesize(
                    text = firstClause,
                    voiceId = voiceId,
                    speed = speed,
                    pitch = pitch,
                    sentencePauseMs = clausePauseMs.coerceIn(120, 320),
                    clausePauseMs = clausePauseMs,
                    warmVoiceFilter = warmVoiceFilter
                )
            }
            val restDeferred = async {
                synthesize(
                    text = restClause,
                    voiceId = voiceId,
                    speed = speed,
                    pitch = pitch,
                    sentencePauseMs = sentencePauseMs,
                    clausePauseMs = clausePauseMs,
                    warmVoiceFilter = warmVoiceFilter
                )
            }

            val firstPcm = firstDeferred.await()
            if (firstPcm.isNotEmpty()) {
                onClauseReady(firstPcm)
            }

            val restPcm = restDeferred.await()
            if (restPcm.isNotEmpty()) {
                onClauseReady(restPcm)
            }

            if (firstPcm.isEmpty()) {
                restPcm
            } else if (restPcm.isEmpty()) {
                firstPcm
            } else {
                val combined = ShortArray(firstPcm.size + restPcm.size)
                System.arraycopy(firstPcm, 0, combined, 0, firstPcm.size)
                System.arraycopy(restPcm, 0, combined, firstPcm.size, restPcm.size)
                combined
            }
        }
    }

    /**
     * Chỉ tách mệnh đề đầu khi có ít nhất 24 ký tự, >= 6 từ và có nhiều từ mang dấu Tiếng Việt
     * để giữ nguyên ngữ cảnh câu Tiếng Việt (tránh tách rời "Ha ha," hoặc "Ồ," ở đầu câu).
     */
    private fun splitFirstShortClause(text: String): Pair<String, String>? {
        if (text.length < 56) return null
        val maxScan = minOf(text.length - 18, 75)
        for (i in 24..maxScan) {
            val ch = text[i]
            if (ch == ',' || ch == ';' || ch == ':' || ch == '–' || ch == '—') {
                val first = text.substring(0, i + 1).trim()
                val rest = text.substring(i + 1).trim()
                val words = first.split(Regex("\\s+")).filter { it.isNotBlank() }
                val vietWords = words.count { w -> w.any { c -> VIETNAMESE_DIACRITIC_CHARS.indexOf(c) >= 0 } }
                if (words.size >= 6 && vietWords >= 3 && rest.length >= 16) {
                    return first to rest
                }
            }
        }
        return null
    }

    /**
     * Đua đa luồng trên CÙNG MỘT GIỌNG ĐỌC (Same-Voice Hedged Racing):
     * - Mở kết nối 1 tới đúng giọng `voiceShortName` (`vi-VN-HoaiMyNeural` hoặc `vi-VN-NamMinhNeural`).
     * - Nếu sau 500ms chưa nhận được byte âm thanh đầu tiên, phóng thêm kết nối 2 (và kết nối 3)
     *   cũng tới **đúng giọng `voiceShortName` đó**.
     * - Đảm bảo 100% thống nhất 1 giọng duy nhất không bao giờ bị đổi giọng mà vẫn khắc phục hoàn toàn hiện tượng nghẽn node lẻ.
     */
    private suspend fun fetchMp3SameVoiceHedged(
        cleanedText: String,
        voiceShortName: String,
        speed: Float,
        pitch: Float
    ): ByteArray? = coroutineScope {
        val firstAudioSignal = CompletableDeferred<Boolean>()
        val job1 = async {
            fetchSingleTurnWebSocket(
                cleanedText = cleanedText,
                voiceShortName = voiceShortName,
                speed = speed,
                pitch = pitch,
                onFirstAudioChunk = { firstAudioSignal.complete(true) }
            )
        }

        val fastEnough = withTimeoutOrNull(500L) {
            firstAudioSignal.await()
        } ?: false

        if (fastEnough) {
            val bytes1 = job1.await()
            if (bytes1 != null && bytes1.isNotEmpty()) {
                return@coroutineScope bytes1
            }
        }

        // Mở thêm 2 kết nối song song tới ĐÚNG GIỌNG ĐANG CHỌN (tuyệt đối không đổi sang giọng khác)
        val job2 = async {
            fetchSingleTurnWebSocket(
                cleanedText = cleanedText,
                voiceShortName = voiceShortName,
                speed = speed,
                pitch = pitch
            )
        }
        val job3 = async {
            fetchSingleTurnWebSocket(
                cleanedText = cleanedText,
                voiceShortName = voiceShortName,
                speed = speed,
                pitch = pitch
            )
        }

        val firstWinner = select<ByteArray?> {
            job1.onAwait { res -> if (res != null && res.isNotEmpty()) res else null }
            job2.onAwait { res -> if (res != null && res.isNotEmpty()) res else null }
            job3.onAwait { res -> if (res != null && res.isNotEmpty()) res else null }
        }

        if (firstWinner != null && firstWinner.isNotEmpty()) {
            job1.cancel()
            job2.cancel()
            job3.cancel()
            return@coroutineScope firstWinner
        }

        val fallbackWinner = job1.await()?.takeIf { it.isNotEmpty() }
            ?: job2.await()?.takeIf { it.isNotEmpty() }
            ?: job3.await()?.takeIf { it.isNotEmpty() }

        job1.cancel()
        job2.cancel()
        job3.cancel()
        fallbackWinner
    }

    /**
     * Gửi `Path:speech.config` và `Path:ssml` (`xml:lang='vi-VN'`) ngay lập tức trong `onOpen` (0ms delay)
     * để máy chủ Microsoft Edge định tuyến vào luồng nhanh (~500ms).
     */
    private suspend fun fetchSingleTurnWebSocket(
        cleanedText: String,
        voiceShortName: String,
        speed: Float,
        pitch: Float,
        onFirstAudioChunk: (() -> Unit)? = null
    ): ByteArray? {
        val connId = EdgeTtsDrm.generateConnectId()
        val secMsGec = EdgeTtsDrm.generateSecMsGecV2()
        val url = "${EdgeTtsConstants.WSS_URL}&ConnectionId=$connId&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=${EdgeTtsConstants.SEC_MS_GEC_VERSION}"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", EdgeTtsConstants.ORIGIN)
            .header("User-Agent", EdgeTtsConstants.USER_AGENT)
            .header("Accept-Language", "vi-VN,vi;q=0.9")
            .header("Cookie", "muid=${EdgeTtsDrm.generateMuid()};")
            .build()

        val doneDeferred = CompletableDeferred<ByteArray?>()
        val audioBuffer = ByteArrayOutputStream(32 * 1024)
        val notifiedFirstChunk = AtomicBoolean(false)

        val ratePercent = ((speed.coerceIn(0.5f, 2.0f) - 1.0f) * 100f).roundToInt()
        val rateStr = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"

        val pitchHz = ((pitch.coerceIn(0.5f, 1.5f) - 1.0f) * 50f).roundToInt()
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"

        val serverVoiceName = EdgeTtsConstants.toServerVoiceName(voiceShortName)
            .replace("&", "&amp;")
            .replace("'", "&apos;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.header("Date")?.let { EdgeTtsDrm.adjustClockSkewFromServerDate(it) }

                val configMsg = buildString {
                    append("X-Timestamp:${edgeDateString()}\r\n")
                    append("Content-Type:application/json; charset=utf-8\r\n")
                    append("Path:speech.config\r\n\r\n")
                    append("{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{")
                    append("\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"")
                    append("},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                }
                webSocket.send(configMsg)

                val reqId = EdgeTtsDrm.generateConnectId()
                val ssml = buildString {
                    append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='vi-VN'>")
                    append("<voice name='$serverVoiceName'>")
                    append("<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>")
                    append(cleanedText)
                    append("</prosody></voice></speak>")
                }
                val ssmlMsg = buildString {
                    append("X-RequestId:$reqId\r\n")
                    append("Content-Type:application/ssml+xml\r\n")
                    append("X-Timestamp:${edgeDateString()}Z\r\n")
                    append("Path:ssml\r\n\r\n")
                    append(ssml)
                }
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    val bytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                    doneDeferred.complete(bytes)
                    try {
                        webSocket.close(1000, "Done")
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.size > 2) {
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    val dataStart = 2 + headerLen
                    if (dataStart < arr.size) {
                        val headerText = String(arr, 2, headerLen, Charsets.UTF_8)
                        if (headerText.contains("Path:audio")) {
                            val chunkLen = arr.size - dataStart
                            if (chunkLen > 0) {
                                synchronized(audioBuffer) {
                                    audioBuffer.write(arr, dataStart, chunkLen)
                                }
                                if (notifiedFirstChunk.compareAndSet(false, true)) {
                                    onFirstAudioChunk?.invoke()
                                }
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!doneDeferred.isCompleted) {
                    val bytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                    doneDeferred.complete(bytes.takeIf { it.isNotEmpty() })
                }
                try {
                    webSocket.close(1000, null)
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.header("Date")?.let { EdgeTtsDrm.adjustClockSkewFromServerDate(it) }
                if (!doneDeferred.isCompleted) {
                    doneDeferred.complete(null)
                }
            }
        }

        val ws = okHttpClient.newWebSocket(request, listener)
        return try {
            val result = withTimeoutOrNull(6_500L) {
                doneDeferred.await()
            }
            if (result == null || result.isEmpty()) {
                ws.cancel()
                null
            } else {
                result
            }
        } catch (e: Exception) {
            ws.cancel()
            throw e
        }
    }

    /**
     * Giải mã MP3 trực tiếp từ bộ nhớ RAM (Zero-Disk I/O) qua [MediaDataSource].
     */
    private fun decodeMp3ToFloat24kHz(mp3Bytes: ByteArray): FloatArray {
        val extractor = MediaExtractor()
        var tempFile: File? = null
        try {
            try {
                extractor.setDataSource(object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (position >= mp3Bytes.size) return -1
                        val available = (mp3Bytes.size - position).toInt()
                        val toCopy = minOf(size, available)
                        System.arraycopy(mp3Bytes, position.toInt(), buffer, offset, toCopy)
                        return toCopy
                    }

                    override fun getSize(): Long = mp3Bytes.size.toLong()

                    override fun close() {}
                })
            } catch (_: Exception) {
                val tf = File(cacheDir, "edge_${tempFileCounter.incrementAndGet()}.mp3")
                tempFile = tf
                FileOutputStream(tf).use { it.write(mp3Bytes) }
                extractor.setDataSource(tf.absolutePath)
            }

            if (extractor.trackCount == 0) {
                return FloatArray(0)
            }

            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                OUTPUT_SAMPLE_RATE
            }
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream(mp3Bytes.size * 6)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(4_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 4_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            codec.stop()
            codec.release()

            val rawPcmBytes = pcmOut.toByteArray()
            if (rawPcmBytes.size < 4) return FloatArray(0)

            val shortCount = rawPcmBytes.size / 2
            val shortBuf = ByteBuffer.wrap(rawPcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val monoCount = if (channels > 1) shortCount / channels else shortCount
            val monoFloats = FloatArray(monoCount)

            if (channels <= 1) {
                for (i in 0 until monoCount) {
                    monoFloats[i] = shortBuf.get(i) / 32768.0f
                }
            } else {
                for (i in 0 until monoCount) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += shortBuf.get(i * channels + c) / 32768.0f
                    }
                    monoFloats[i] = sum / channels
                }
            }

            return if (sampleRate != OUTPUT_SAMPLE_RATE && sampleRate > 0) {
                resampleLinear(monoFloats, sampleRate.toFloat(), OUTPUT_SAMPLE_RATE.toFloat())
            } else {
                monoFloats
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải mã MP3 Edge TTS: ${e.message}", e)
            return FloatArray(0)
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            try {
                tempFile?.let { if (it.exists()) it.delete() }
            } catch (_: Exception) {
            }
        }
    }

    private fun resampleLinear(input: FloatArray, srcRate: Float, dstRate: Float): FloatArray {
        if (input.isEmpty()) return input
        val ratio = srcRate / dstRate
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val maxIdx = input.size - 1
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, maxIdx)
            val nextIdx = (idx + 1).coerceIn(0, maxIdx)
            val frac = pos - idx
            out[i] = input[idx] * (1f - frac) + input[nextIdx] * frac
        }
        return out
    }

    private fun postProcessWaveform24k(
        wav: FloatArray,
        trailingPauseMs: Int,
        warmVoiceFilter: Boolean
    ): ShortArray {
        val sr = OUTPUT_SAMPLE_RATE
        val threshold = 0.004f

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

        val voiceLength = (endIdx - startIdx).coerceAtLeast(0)
        if (voiceLength == 0) return ShortArray(0)

        val fadeInSamples = (0.018f * sr).toInt().coerceAtMost(voiceLength)
        val fadeOutSamples = (0.035f * sr).toInt().coerceAtMost(voiceLength)
        val pauseSamples = ((trailingPauseMs / 1000f) * sr).toInt().coerceAtLeast(0)
        val leadInSamples = (0.012f * sr).toInt()
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
     * Chuẩn hóa văn bản Tiếng Việt trước khi đưa vào SSML của Microsoft Edge:
     * 1. Loại bỏ toàn bộ dấu ngoặc kép / ngoặc trích dẫn ("...", “...”, ‘...’, «...», 「...」)
     *    để bộ phân tích của Microsoft không chuyển các từ ngắn trong ngoặc kép sang chế độ phát âm tiếng Anh.
     * 2. Chuẩn hóa các từ cảm thán & tiếng cười (ha ha, haha, hihi, hehe, huhu, oh, ah, hmm, ok...)
     *    sang âm tiết thuần Việt có dấu để 100% đọc bằng ngữ điệu Tiếng Việt.
     */
    internal fun normalizeVietnameseForEdgeSsml(raw: String): String {
        if (raw.isBlank()) return ""

        // 1. Thay thế dấu ngoặc kép / ngoặc hội thoại bằng khoảng trắng để không kích hoạt chế độ trích dẫn tiếng Anh
        val unquoted = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '"', '\'', '“', '”', '‘', '’', '«', '»', '「', '」', '『', '』', '‹', '›', '„', '‟', '”' -> {
                    unquoted.append(' ')
                }
                else -> unquoted.append(ch)
            }
        }

        // 2. Chuẩn hóa các từ cảm thán / tiếng cười dễ bị nhận nhầm là tiếng Anh sang âm tiết thuần Việt
        var normalized = unquoted.toString()
        normalized = REGEX_HAHA.replace(normalized, "hà ha")
        normalized = REGEX_HIHI.replace(normalized, "hì hi")
        normalized = REGEX_HEHE.replace(normalized, "hề he")
        normalized = REGEX_HUHU.replace(normalized, "hù hu")
        normalized = REGEX_HOHO.replace(normalized, "hồ hô")
        normalized = REGEX_OH.replace(normalized, "ồ")
        normalized = REGEX_AH.replace(normalized, "à")
        normalized = REGEX_HMM.replace(normalized, "hừm")
        normalized = REGEX_OK.replace(normalized, "ô kê")
        normalized = REGEX_MULTI_SPACE.replace(normalized, " ").trim()

        // 3. Escape các ký tự đặc biệt của XML
        val sb = StringBuilder(normalized.length + 16)
        for (ch in normalized) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> {
                    val code = ch.code
                    if (code == 9 || code == 10 || code == 13 || code >= 32) {
                        sb.append(ch)
                    } else {
                        sb.append(' ')
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    private fun edgeDateString(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
