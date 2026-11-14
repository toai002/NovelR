package com.novelr.reader.model

import com.novelr.reader.util.ReaderParagraph

enum class PlaybackStatus {
    IDLE,
    PLAYING,
    PAUSED,
    COMPLETED
}

enum class TtsEngineType {
    NATIVE_SYSTEM,      // Hệ thống Android TTS (Google, Samsung...)
    EDGE_ONLINE,        // Microsoft Edge TTS Trực tiếp (Hoài My, Nam Minh + 6 giọng Đa Ngôn Ngữ)
    PIPER_ON_DEVICE,    // Piper VITS Offline (73 giọng đa dạng: 8 giọng HD rõ chữ + 65 giọng VIVOS, siêu nhanh ~100ms)
    VIENEU_ON_DEVICE    // VieNeu-TTS v3 Nano AI chạy ONNX On-Device offline (Truyền cảm)
}

enum class VieNeuModelStatus {
    NOT_DOWNLOADED,     // Chưa tải model về máy
    DOWNLOADING,        // Đang tải weights ONNX
    READY,              // Đã sẵn sàng chạy offline
    ERROR               // Lỗi nạp hoặc tải model
}

enum class ReadingTheme(val label: String) {
    SEPIA("Giấy ấm"),
    LIGHT("Sáng"),
    DARK("Tối dịu")
}

data class VieNeuVoice(
    val id: String,
    val name: String,
    val gender: String,
    val description: String
)

data class SystemVoiceInfo(
    val name: String,        // ID giọng của Android TTS (ví dụ: vi-vn-x-vid-local)
    val label: String,       // Tên hiển thị thân thiện (ví dụ: Nam Trầm Ấm - Khuyên dùng)
    val isOffline: Boolean   // Chạy offline không cần mạng
)

data class EngineInfo(
    val label: String,
    val packageName: String
)

data class TtsSettings(
    val speechRate: Float = DEFAULT_SPEECH_RATE,
    val pitch: Float = DEFAULT_PITCH,
    val enginePackage: String? = null,
    val selectedSystemVoice: String? = null,
    val engineType: TtsEngineType = TtsEngineType.NATIVE_SYSTEM,
    val selectedVieNeuVoice: String = DEFAULT_VIENEU_VOICE,
    val selectedPiperVoice: String = DEFAULT_PIPER_VOICE,
    val selectedEdgeVoice: String = DEFAULT_EDGE_VOICE,
    val lookaheadBufferSize: Int = DEFAULT_LOOKAHEAD,
    val sentencePauseMs: Int = DEFAULT_SENTENCE_PAUSE_MS,
    val clausePauseMs: Int = DEFAULT_CLAUSE_PAUSE_MS,
    val warmVoiceFilter: Boolean = DEFAULT_WARM_VOICE_FILTER, // Bộ lọc làm ấm giọng, giảm tần số cao chói tai
    val backgroundPlayback: Boolean = true,
    val headsetAutoPlayPause: Boolean = true // Tự động phát khi kết nối tai nghe, tự động dừng khi rút/ngắt kết nối tai nghe
) {
    companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_VIENEU_VOICE = "duc_tri"
        const val DEFAULT_PIPER_VOICE = "vais_default"
        const val DEFAULT_EDGE_VOICE = "en-US-AvaMultilingualNeural"
        const val DEFAULT_LOOKAHEAD = 8
        const val DEFAULT_SENTENCE_PAUSE_MS = 550
        const val DEFAULT_CLAUSE_PAUSE_MS = 240
        const val DEFAULT_WARM_VOICE_FILTER = true
    }

    val isDefaultVoiceConfig: Boolean
        get() = speechRate == DEFAULT_SPEECH_RATE &&
                pitch == DEFAULT_PITCH &&
                selectedVieNeuVoice == DEFAULT_VIENEU_VOICE &&
                selectedPiperVoice == DEFAULT_PIPER_VOICE &&
                selectedEdgeVoice == DEFAULT_EDGE_VOICE &&
                selectedSystemVoice == null &&
                lookaheadBufferSize == DEFAULT_LOOKAHEAD &&
                sentencePauseMs == DEFAULT_SENTENCE_PAUSE_MS &&
                clausePauseMs == DEFAULT_CLAUSE_PAUSE_MS &&
                warmVoiceFilter == DEFAULT_WARM_VOICE_FILTER

    /**
     * Khôi phục toàn bộ thông số chỉnh giọng (tốc độ, cao độ, khoảng nghỉ, nhân vật, bộ lọc ấm)
     * về mặc định chuẩn nhưng giữ nguyên loại Engine đang chọn.
     */
    fun resetVoiceToDefault(): TtsSettings = copy(
        speechRate = DEFAULT_SPEECH_RATE,
        pitch = DEFAULT_PITCH,
        selectedVieNeuVoice = DEFAULT_VIENEU_VOICE,
        selectedPiperVoice = DEFAULT_PIPER_VOICE,
        selectedEdgeVoice = DEFAULT_EDGE_VOICE,
        selectedSystemVoice = null,
        lookaheadBufferSize = DEFAULT_LOOKAHEAD,
        sentencePauseMs = DEFAULT_SENTENCE_PAUSE_MS,
        clausePauseMs = DEFAULT_CLAUSE_PAUSE_MS,
        warmVoiceFilter = DEFAULT_WARM_VOICE_FILTER
    )
}

data class ReaderUiState(
    val rawText: String = "",
    val bookTitle: String = "",
    val paragraphs: List<ReaderParagraph> = emptyList(),
    val sentences: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val fontSizeSp: Float = 19f,
    val readingTheme: ReadingTheme = ReadingTheme.SEPIA,
    val settings: TtsSettings = TtsSettings(),
    val availableEngines: List<EngineInfo> = emptyList(),
    val availableSystemVoices: List<SystemVoiceInfo> = emptyList(),
    val isTtsInitialized: Boolean = false,
    val errorMessage: String? = null,

    // VieNeu AI On-Device States
    val vieneuModelStatus: VieNeuModelStatus = VieNeuModelStatus.NOT_DOWNLOADED,
    val vieneuDownloadProgress: Float = 0f,

    // Piper VITS Multi-Voice Offline States
    val piperModelStatus: VieNeuModelStatus = VieNeuModelStatus.NOT_DOWNLOADED,
    val piperDownloadProgress: Float = 0f,

    val prebufferedIndices: Set<Int> = emptySet(), // Tập hợp các chỉ số câu đã nạp sẵn vào RAM
    val isSynthesizing: Boolean = false            // Đang tính toán suy luận ONNX
)
