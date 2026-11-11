package com.novelr.reader.service.edgetts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.novelr.reader.service.WarmAudioDspProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bộ máy tổng hợp giọng nói trực tiếp Microsoft Edge TTS (Online Neural 24kHz).
 *
 * Tính năng nổi bật:
 * - Tích hợp thuật toán tạo mã `Sec-MS-GEC` v2 (CS64 + Salted SHA-256) mới nhất của Edge 143.
 * - Tự động đồng bộ giờ hệ thống (`Date` header) nếu đồng hồ điện thoại lệch so với máy chủ Microsoft.
 * - Hỗ trợ Hoài My (`vi-VN-HoaiMyNeural`), Nam Minh (`vi-VN-NamMinhNeural`) và 6 giọng Đa Ngôn Ngữ (`MultilingualNeural`).
 * - Tự động retry siêu tốc & dự phòng sang giọng Multilingual cùng giới tính nếu node `vi-VN` bị nghẽn, đảm bảo đọc truyện liền mạch 100%.
 * - Giải mã MP3 phần cứng sang PCM 24kHz và đi qua bộ lọc Chống chói DSP (`WarmAudioDspProcessor`).
 */
class EdgeTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "EdgeTtsEngine"
        private const val OUTPUT_SAMPLE_RATE = 24000
        private val tempFileCounter = AtomicLong(0L)
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cacheDir: File by lazy {
        File(context.cacheDir, "edge_tts_mp3").apply { mkdirs() }
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
        val cleaned = sanitizeTextForSsml(text)
        if (cleaned.isBlank()) return@withContext ShortArray(0)

        val profile = EdgeTtsConstants.findById(voiceId)
        val primaryVoice = profile.shortName

        // Nếu node vi-VN bị quá tải tạm thời sau 2 lần thử, tự động dùng giọng Multilingual cùng giới tính cho câu đó
        val fallbackMultilingualVoice = if (profile.gender.startsWith("Nam")) {
            "en-US-AndrewMultilingualNeural"
        } else {
            "en-US-AvaMultilingualNeural"
        }

        var mp3Bytes: ByteArray? = null

        // Thử tối đa 3 lần với giọng chính
        for (attempt in 1..3) {
            val result = fetchMp3OverWebSocket(
                text = cleaned,
                voiceShortName = primaryVoice,
                speed = speed,
                pitch = pitch
            )
            if (result != null && result.isNotEmpty()) {
                mp3Bytes = result
                break
            }
        }

        // Nếu giọng chính là vi-VN mà máy chủ Microsoft trả rỗng, fallback tức thì sang giọng Multilingual
        if ((mp3Bytes == null || mp3Bytes.isEmpty()) && primaryVoice != fallbackMultilingualVoice) {
            Log.w(TAG, "Node $primaryVoice phản hồi rỗng, chuyển dự phòng câu sang $fallbackMultilingualVoice")
            mp3Bytes = fetchMp3OverWebSocket(
                text = cleaned,
                voiceShortName = fallbackMultilingualVoice,
                speed = speed,
                pitch = pitch
            )
        }

        if (mp3Bytes == null || mp3Bytes.isEmpty()) {
            return@withContext ShortArray(0)
        }

        val floatWav24k = decodeMp3ToFloat24kHz(mp3Bytes)
        if (floatWav24k.isEmpty()) return@withContext ShortArray(0)

        val userSpeed = speed.coerceIn(0.5f, 2.0f)
        val pauseMs = (sentencePauseMs / userSpeed).roundToInt().coerceIn(180, 1500)
        postProcessWaveform24k(floatWav24k, pauseMs, warmVoiceFilter)
    }

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

    private suspend fun fetchMp3OverWebSocket(
        text: String,
        voiceShortName: String,
        speed: Float,
        pitch: Float
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
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${EdgeTtsDrm.generateMuid()};")
            .build()

        val deferred = CompletableDeferred<ByteArray?>()
        val audioBuffer = ByteArrayOutputStream(32 * 1024)

        val ratePercent = ((speed.coerceIn(0.5f, 2.0f) - 1.0f) * 100f).roundToInt()
        val rateStr = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"

        val pitchHz = ((pitch.coerceIn(0.5f, 1.5f) - 1.0f) * 50f).roundToInt()
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.header("Date")?.let { EdgeTtsDrm.adjustClockSkewFromServerDate(it) }

                val configMsg = buildString {
                    append("X-Timestamp:${edgeDateString()}\r\n")
                    append("Content-Type:application/json; charset=utf-8\r\n")
                    append("Path:speech.config\r\n\r\n")
                    append("{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{")
                    append("\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"")
                    append("},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                }
                webSocket.send(configMsg)

                val reqId = EdgeTtsDrm.generateConnectId()
                val ssml = buildString {
                    append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>")
                    append("<voice name='$voiceShortName'>")
                    append("<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>")
                    append(text)
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
                    deferred.complete(bytes)
                    webSocket.close(1000, "Done")
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
                            synchronized(audioBuffer) {
                                audioBuffer.write(arr, dataStart, arr.size - dataStart)
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    val bytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                    deferred.complete(bytes)
                }
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.header("Date")?.let { EdgeTtsDrm.adjustClockSkewFromServerDate(it) }
                Log.w(TAG, "Edge TTS WebSocket lỗi (${response?.code}): ${t.message}")
                if (!deferred.isCompleted) {
                    deferred.complete(null)
                }
            }
        }

        val ws = okHttpClient.newWebSocket(request, listener)
        val result = withTimeoutOrNull(8_500L) {
            deferred.await()
        }
        if (result == null) {
            ws.cancel()
        }
        return result
    }

    private fun decodeMp3ToFloat24kHz(mp3Bytes: ByteArray): FloatArray {
        val tempFile = File(cacheDir, "edge_${tempFileCounter.incrementAndGet()}.mp3")
        try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)
            if (extractor.trackCount == 0) {
                extractor.release()
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
                    val inIdx = codec.dequeueInputBuffer(5_000)
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

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000)
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
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Đợi thêm 1 vòng nếu EOS đang xử lý
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

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
                if (tempFile.exists()) tempFile.delete()
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

    private fun sanitizeTextForSsml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
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
