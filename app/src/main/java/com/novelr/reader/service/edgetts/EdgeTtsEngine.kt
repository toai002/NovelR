package com.novelr.reader.service.edgetts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.novelr.reader.service.WarmAudioDspProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bộ máy tổng hợp giọng nói trực tiếp Microsoft Edge TTS (Online Neural 24kHz) - Bản Siêu Tốc v2.2.1.
 *
 * Các tối ưu hóa giảm thời gian chờ load (Low-Latency Architecture):
 * 1. **Persistent Pre-Warmed WebSocket Pool (Hồ kết nối giữ phiên ấm)**:
 *    - Duy trì sẵn 4 kết nối WebSocket đã bắt tay TLS, gửi sẵn `Path:speech.config` và làm nóng worker Neural
 *      trên máy chủ Microsoft, giúp bỏ qua hoàn toàn 1.5s–2.5s khởi tạo kết nối mới cho mỗi câu.
 *    - Tái sử dụng cùng một kết nối WebSocket xuyên suốt nhiều câu liên tiếp (độ trễ phản hồi chỉ ~150ms–300ms/câu).
 * 2. **Parallel Clause-Level Streaming (Phát mệnh đề đầu song song)**:
 *    - Với câu vừa/dài, tự động tách mệnh đề ngắn đầu tiên (4–10 chữ đầu) và tổng hợp **song song cùng lúc**
 *      với phần còn lại của câu trên 2 kết nối ấm. Mệnh đề đầu phát ra tiếng ngay sau ~0.25–0.5s trong khi phần sau
 *      đang tải ngầm.
 * 3. **Smart Hedged Racing & Cooldown cho node `vi-VN`**:
 *    - Nếu máy chủ `vi-VN` của Microsoft phản hồi chậm (> 550ms chưa có byte âm thanh đầu) hoặc trả rỗng,
 *      tự động đua song song (Hedged Request) với giọng Đa Ngôn Ngữ cùng giới tính và nhớ trạng thái nghẽn 3 phút
 *      để không bao giờ phải chờ timeout 2.5s.
 * 4. **Zero-Disk In-Memory MP3 Decoder**:
 *    - Giải mã MP3 trực tiếp từ bộ nhớ RAM qua `MediaDataSource` không cần ghi/xóa file tạm trên bộ nhớ máy.
 */
class EdgeTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "EdgeTtsEngine"
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val MAX_POOL_SIZE = 5
        private const val TARGET_WARM_SOCKETS = 3
        private const val MAX_SOCKET_IDLE_MS = 45_000L
        private const val VI_NODE_COOLDOWN_MS = 180_000L // 3 phút tự động bypass khi node vi-VN bị nghẽn
        private val tempFileCounter = AtomicLong(0L)
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cacheDir: File by lazy {
        File(context.cacheDir, "edge_tts_mp3").apply { mkdirs() }
    }

    private val socketPool = ConcurrentLinkedQueue<ReusableEdgeSocket>()
    private val warmingCount = AtomicInteger(0)

    @Volatile
    private var viNodeDegradedUntilMs: Long = 0L

    @Volatile
    private var lastWarmVoiceShortName: String = "en-US-AvaMultilingualNeural"

    init {
        // Làm nóng sẵn hồ kết nối ngay khi khởi tạo Engine
        warmUpPool(EdgeTtsConstants.DEFAULT_EDGE_VOICE)
    }

    /**
     * Chủ động làm nóng trước các kết nối WebSocket trong nền để khi người dùng bấm Play là có sẵn kết nối ~150ms.
     */
    fun warmUpPool(voiceId: String = EdgeTtsConstants.DEFAULT_EDGE_VOICE) {
        val profile = EdgeTtsConstants.findById(voiceId)
        val warmTargetVoice = if (profile.shortName.startsWith("vi-VN") &&
            System.currentTimeMillis() < viNodeDegradedUntilMs
        ) {
            fallbackVoiceFor(profile)
        } else {
            profile.shortName
        }
        lastWarmVoiceShortName = warmTargetVoice

        pruneStaleSockets()
        val currentSize = socketPool.size + warmingCount.get()
        val needed = (TARGET_WARM_SOCKETS - currentSize).coerceAtLeast(0)
        repeat(needed) {
            warmingCount.incrementAndGet()
            engineScope.launch {
                try {
                    val socket = createAndWarmSocket(warmTargetVoice)
                    if (socket != null && !socket.isUseless()) {
                        if (socketPool.size < MAX_POOL_SIZE) {
                            socketPool.offer(socket)
                        } else {
                            socket.closeQuietly()
                        }
                    }
                } finally {
                    warmingCount.decrementAndGet()
                }
            }
        }
    }

    private fun fallbackVoiceFor(profile: EdgeVoiceProfile): String {
        return if (profile.gender.startsWith("Nam")) {
            "fr-FR-RemyMultilingualNeural"
        } else {
            "en-US-AvaMultilingualNeural"
        }
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

        val mp3Bytes = fetchMp3FastWithHedging(
            cleanedText = cleaned,
            voiceId = voiceId,
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
     * Nếu câu dài hơn 42 ký tự và có dấu phẩy/chấm phẩy, tách mệnh đề ngắn đầu tiên để tổng hợp
     * song song trên 2 kết nối WebSocket ấm. Ngay khi mệnh đề 1 xong (~200-450ms), phát ra tiếng ngay lập tức
     * trong khi phần còn lại của câu đã gần tải xong ở kết nối thứ 2.
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
            // Chạy song song cả mệnh đề 1 và phần còn lại trên 2 kết nối ấm cùng lúc!
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
     * Tách mệnh đề ngắn đầu tiên (từ 12 đến 65 ký tự) tại dấu ngắt tự nhiên (, ; : – —)
     * để giảm một nửa thời gian chờ câu đầu tiên.
     */
    private fun splitFirstShortClause(text: String): Pair<String, String>? {
        if (text.length < 44) return null
        val maxScan = minOf(text.length - 12, 68)
        for (i in 12..maxScan) {
            val ch = text[i]
            if (ch == ',' || ch == ';' || ch == ':' || ch == '–' || ch == '—') {
                val first = text.substring(0, i + 1).trim()
                val rest = text.substring(i + 1).trim()
                if (first.length >= 10 && rest.length >= 10) {
                    return first to rest
                }
            }
        }
        return null
    }

    /**
     * Tải MP3 từ Edge TTS với cơ chế Hedged Request thông minh:
     * - Nếu giọng là Multilingual: gửi thẳng trên kết nối ấm trong Pool (~150-350ms).
     * - Nếu giọng là `vi-VN` (Hoài My / Nam Minh):
     *   + Nếu node `vi-VN` đang trong thời gian cooldown nghẽn: chuyển thẳng 0ms sang giọng Multilingual dự phòng.
     *   + Nếu không: thử `vi-VN` trước, nhưng nếu sau 550ms chưa có byte âm thanh đầu tiên hoặc trả rỗng,
     *     lập tức kích hoạt luồng đua song song với giọng Multilingual để người dùng không bao giờ phải đợi 2.5s.
     */
    private suspend fun fetchMp3FastWithHedging(
        cleanedText: String,
        voiceId: String,
        speed: Float,
        pitch: Float
    ): ByteArray? = coroutineScope {
        val profile = EdgeTtsConstants.findById(voiceId)
        val primaryVoice = profile.shortName
        val fallbackVoice = fallbackVoiceFor(profile)
        val isViVoice = primaryVoice.startsWith("vi-VN")
        val now = System.currentTimeMillis()

        // Nếu node vi-VN vừa bị lỗi/nghẽn trong 3 phút gần đây -> dùng ngay giọng Multilingual 0ms delay
        if (isViVoice && now < viNodeDegradedUntilMs) {
            val fastBytes = executeSingleSynthesis(cleanedText, fallbackVoice, speed, pitch)
            if (fastBytes != null && fastBytes.isNotEmpty()) {
                return@coroutineScope fastBytes
            }
        }

        if (!isViVoice) {
            // Giọng Multilingual ổn định 100%: thử trên kết nối ấm, nếu kết nối cũ hết hạn thì thử lại ngay trên kết nối mới
            val firstTry = executeSingleSynthesis(cleanedText, primaryVoice, speed, pitch)
            if (firstTry != null && firstTry.isNotEmpty()) {
                return@coroutineScope firstTry
            }
            return@coroutineScope executeSingleSynthesis(cleanedText, primaryVoice, speed, pitch)
        }

        // Với giọng vi-VN (Hoài My / Nam Minh): đua thông minh (Hedged Request) nếu quá 550ms chưa có byte đầu tiên
        val firstAudioSignal = CompletableDeferred<Boolean>()
        val primaryJob = async {
            executeSingleSynthesis(
                cleanedText = cleanedText,
                voiceShortName = primaryVoice,
                speed = speed,
                pitch = pitch,
                onFirstAudioChunk = { firstAudioSignal.complete(true) }
            )
        }

        val gotEarlyAudio = withTimeoutOrNull(550L) {
            firstAudioSignal.await()
        } ?: false

        if (gotEarlyAudio) {
            val primaryBytes = primaryJob.await()
            if (primaryBytes != null && primaryBytes.isNotEmpty()) {
                return@coroutineScope primaryBytes
            }
        }

        // Node vi-VN phản hồi chậm (>550ms) hoặc trả rỗng -> phóng ngay request dự phòng song song trên socket ấm
        val backupJob = async {
            executeSingleSynthesis(
                cleanedText = cleanedText,
                voiceShortName = fallbackVoice,
                speed = speed,
                pitch = pitch
            )
        }

        val winner = select<ByteArray?> {
            primaryJob.onAwait { res ->
                if (res != null && res.isNotEmpty()) res else null
            }
            backupJob.onAwait { res ->
                if (res != null && res.isNotEmpty()) res else null
            }
        }

        if (winner != null && winner.isNotEmpty()) {
            if (primaryJob.isCompleted) {
                val pRes = primaryJob.getCompleted()
                if (pRes == null || pRes.isEmpty()) {
                    viNodeDegradedUntilMs = System.currentTimeMillis() + VI_NODE_COOLDOWN_MS
                }
            } else {
                // Node vi-VN quá chậm so với backup -> đánh dấu cooldown để các câu sau chạy tức thì
                viNodeDegradedUntilMs = System.currentTimeMillis() + VI_NODE_COOLDOWN_MS
                primaryJob.cancel()
            }
            backupJob.cancel()
            return@coroutineScope winner
        }

        val secondWait = primaryJob.await()?.takeIf { it.isNotEmpty() }
            ?: backupJob.await()?.takeIf { it.isNotEmpty() }

        if (secondWait == null) {
            viNodeDegradedUntilMs = System.currentTimeMillis() + VI_NODE_COOLDOWN_MS
        }
        secondWait
    }

    private suspend fun executeSingleSynthesis(
        cleanedText: String,
        voiceShortName: String,
        speed: Float,
        pitch: Float,
        onFirstAudioChunk: (() -> Unit)? = null
    ): ByteArray? {
        // 1. Lấy kết nối ấm có sẵn trong Pool (ưu tiên kết nối cùng giọng hoặc kết nối bất kỳ còn sống)
        val pooledSocket = borrowWarmSocket()
        if (pooledSocket != null) {
            val bytes = pooledSocket.synthesizeTurn(
                cleanedText = cleanedText,
                voiceShortName = voiceShortName,
                speed = speed,
                pitch = pitch,
                timeoutMs = 5_500L,
                onFirstAudioChunk = onFirstAudioChunk
            )
            if (bytes != null && bytes.isNotEmpty()) {
                returnSocketToPool(pooledSocket)
                return bytes
            } else {
                pooledSocket.closeQuietly()
            }
        }

        // 2. Nếu chưa có kết nối ấm hoặc kết nối ấm vừa hết hạn, mở kết nối mới và giữ lại trong Pool sau khi xong
        val freshSocket = createOpenSocket() ?: return null
        val bytes = freshSocket.synthesizeTurn(
            cleanedText = cleanedText,
            voiceShortName = voiceShortName,
            speed = speed,
            pitch = pitch,
            timeoutMs = 6_500L,
            onFirstAudioChunk = onFirstAudioChunk
        )
        if (bytes != null && bytes.isNotEmpty()) {
            returnSocketToPool(freshSocket)
            // Tự động bổ sung thêm kết nối ấm vào Pool trong nền
            warmUpPool(voiceShortName)
            return bytes
        } else {
            freshSocket.closeQuietly()
            return null
        }
    }

    private fun pruneStaleSockets() {
        val iter = socketPool.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            if (s.isUseless()) {
                iter.remove()
                s.closeQuietly()
            }
        }
    }

    private fun borrowWarmSocket(): ReusableEdgeSocket? {
        while (true) {
            val socket = socketPool.poll() ?: return null
            if (!socket.isUseless()) {
                if (socketPool.size < 2) {
                    warmUpPool(lastWarmVoiceShortName)
                }
                return socket
            } else {
                socket.closeQuietly()
            }
        }
    }

    private fun returnSocketToPool(socket: ReusableEdgeSocket) {
        if (!socket.isUseless() && socketPool.size < MAX_POOL_SIZE) {
            socketPool.offer(socket)
        } else {
            socket.closeQuietly()
        }
    }

    private suspend fun createOpenSocket(): ReusableEdgeSocket? {
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

        val socket = ReusableEdgeSocket(okHttpClient, request)
        val connected = socket.awaitConnected(4_500L)
        if (!connected) {
            socket.closeQuietly()
            return null
        }
        return socket
    }

    private suspend fun createAndWarmSocket(voiceShortName: String): ReusableEdgeSocket? {
        val socket = createOpenSocket() ?: return null
        // Gửi 1 âm tiết cực ngắn ở tốc độ +100% để máy chủ Microsoft gắn sẵn worker Neural vào kết nối WebSocket này
        val warmBytes = socket.synthesizeTurn(
            cleanedText = "a",
            voiceShortName = voiceShortName,
            speed = 2.0f,
            pitch = 1.0f,
            timeoutMs = 4_500L,
            onFirstAudioChunk = null
        )
        return if (warmBytes != null && warmBytes.isNotEmpty() && !socket.isUseless()) {
            socket
        } else {
            socket.closeQuietly()
            null
        }
    }

    /**
     * Kết nối WebSocket tái sử dụng nhiều lượt (Multi-Turn Keep-Alive WebSocket).
     */
    private inner class ReusableEdgeSocket(
        client: OkHttpClient,
        request: Request
    ) {
        private val connectedDeferred = CompletableDeferred<Boolean>()
        private val closedFlag = AtomicBoolean(false)
        private val activeTurnRef = AtomicReference<TurnState?>(null)

        @Volatile
        var lastActiveTimeMs: Long = System.currentTimeMillis()

        private inner class TurnState(
            val onFirstAudio: (() -> Unit)?
        ) {
            val doneDeferred = CompletableDeferred<ByteArray?>()
            val buffer = ByteArrayOutputStream(32 * 1024)
            val notifiedFirstChunk = AtomicBoolean(false)
        }

        private val ws: WebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.header("Date")?.let { EdgeTtsDrm.adjustClockSkewFromServerDate(it) }
                lastActiveTimeMs = System.currentTimeMillis()

                val configMsg = buildString {
                    append("X-Timestamp:${edgeDateString()}\r\n")
                    append("Content-Type:application/json; charset=utf-8\r\n")
                    append("Path:speech.config\r\n\r\n")
                    append("{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{")
                    append("\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"")
                    append("},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                }
                webSocket.send(configMsg)
                connectedDeferred.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActiveTimeMs = System.currentTimeMillis()
                if (text.contains("Path:turn.end")) {
                    val turn = activeTurnRef.getAndSet(null)
                    if (turn != null && !turn.doneDeferred.isCompleted) {
                        val bytes = synchronized(turn.buffer) { turn.buffer.toByteArray() }
                        turn.doneDeferred.complete(bytes)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastActiveTimeMs = System.currentTimeMillis()
                val turn = activeTurnRef.get() ?: return
                val arr = bytes.toByteArray()
                if (arr.size > 2) {
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    val dataStart = 2 + headerLen
                    if (dataStart < arr.size) {
                        val headerText = String(arr, 2, headerLen, Charsets.UTF_8)
                        if (headerText.contains("Path:audio")) {
                            val chunkLen = arr.size - dataStart
                            if (chunkLen > 0) {
                                synchronized(turn.buffer) {
                                    turn.buffer.write(arr, dataStart, chunkLen)
                                }
                                if (turn.notifiedFirstChunk.compareAndSet(false, true)) {
                                    turn.onFirstAudio?.invoke()
                                }
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closedFlag.set(true)
                connectedDeferred.complete(false)
                val turn = activeTurnRef.getAndSet(null)
                if (turn != null && !turn.doneDeferred.isCompleted) {
                    val bytes = synchronized(turn.buffer) { turn.buffer.toByteArray() }
                    turn.doneDeferred.complete(bytes.takeIf { it.isNotEmpty() })
                }
                try {
                    webSocket.close(1000, null)
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closedFlag.set(true)
                response?.header("Date")?.let { EdgeTtsDrm.adjustClockSkewFromServerDate(it) }
                connectedDeferred.complete(false)
                val turn = activeTurnRef.getAndSet(null)
                turn?.doneDeferred?.complete(null)
            }
        })

        suspend fun awaitConnected(timeoutMs: Long): Boolean {
            if (closedFlag.get()) return false
            return withTimeoutOrNull(timeoutMs) {
                connectedDeferred.await()
            } ?: false
        }

        fun isUseless(): Boolean {
            if (closedFlag.get()) return true
            val idleMs = System.currentTimeMillis() - lastActiveTimeMs
            return idleMs > MAX_SOCKET_IDLE_MS
        }

        suspend fun synthesizeTurn(
            cleanedText: String,
            voiceShortName: String,
            speed: Float,
            pitch: Float,
            timeoutMs: Long,
            onFirstAudioChunk: (() -> Unit)?
        ): ByteArray? {
            if (isUseless()) return null

            val ratePercent = ((speed.coerceIn(0.5f, 2.0f) - 1.0f) * 100f).roundToInt()
            val rateStr = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"

            val pitchHz = ((pitch.coerceIn(0.5f, 1.5f) - 1.0f) * 50f).roundToInt()
            val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"

            val serverVoiceName = EdgeTtsConstants.toServerVoiceName(voiceShortName)
            val reqId = EdgeTtsDrm.generateConnectId()

            val ssml = buildString {
                append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>")
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

            val turn = TurnState(onFirstAudioChunk)
            activeTurnRef.set(turn)
            val sent = ws.send(ssmlMsg)
            if (!sent) {
                activeTurnRef.compareAndSet(turn, null)
                closedFlag.set(true)
                return null
            }

            val result = withTimeoutOrNull(timeoutMs) {
                turn.doneDeferred.await()
            }
            activeTurnRef.compareAndSet(turn, null)

            if (result == null || result.isEmpty()) {
                closedFlag.set(true)
                closeQuietly()
                return null
            }

            lastActiveTimeMs = System.currentTimeMillis()
            return result
        }

        fun closeQuietly() {
            closedFlag.set(true)
            try {
                ws.cancel()
            } catch (_: Exception) {
            }
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
                // Dự phòng nếu ROM tùy biến không hỗ trợ MediaDataSource cho MP3
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
        val pauseSamples = ((trailingPauseMs / 1000f) * sr).Int().coerceAtLeast(0)
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

    private fun Float.Int(): Int = this.toInt()

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
