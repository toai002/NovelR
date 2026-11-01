package com.novelr.reader.model

enum class PlaybackStatus {
    IDLE,
    PLAYING,
    PAUSED,
    COMPLETED
}

enum class TtsEngineType {
    NATIVE_SYSTEM,      // Hệ thống Android TTS (Google, Samsung...)
    VIENEU_ON_DEVICE    // VieNeu-TTS v3 Nano AI chạy ONNX On-Device offline
}

enum class VieNeuModelStatus {
    NOT_DOWNLOADED,     // Chưa tải model về máy (~256 MB trên HuggingFace)
    DOWNLOADING,        // Đang tải weights ONNX
    READY,              // Đã sẵn sàng chạy offline
    ERROR               // Lỗi nạp hoặc tải model
}

data class VieNeuVoice(
    val id: String,
    val name: String,
    val gender: String,
    val description: String
)

data class EngineInfo(
    val label: String,
    val packageName: String
)

data class TtsSettings(
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val enginePackage: String? = null,
    val engineType: TtsEngineType = TtsEngineType.NATIVE_SYSTEM,
    val selectedVieNeuVoice: String = "duc_tri",
    val lookaheadBufferSize: Int = 2,   // Số câu tổng hợp trước vào RAM
    val sentencePauseMs: Int = 550,     // Khoảng nghỉ tự nhiên khi chuyển câu (ms)
    val clausePauseMs: Int = 240,       // Khoảng nghỉ lấy hơi giữa các mệnh đề dấu phẩy (ms)
    val backgroundPlayback: Boolean = true // Tiếp tục đọc chạy nền khi tắt màn hình / ẩn ứng dụng
)

data class ReaderUiState(
    val rawText: String = "",
    val bookTitle: String = "",
    val sentences: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val fontSizeSp: Float = 18f,
    val settings: TtsSettings = TtsSettings(),
    val availableEngines: List<EngineInfo> = emptyList(),
    val isTtsInitialized: Boolean = false,
    val errorMessage: String? = null,

    // VieNeu AI On-Device States
    val vieneuModelStatus: VieNeuModelStatus = VieNeuModelStatus.NOT_DOWNLOADED,
    val vieneuDownloadProgress: Float = 0f,
    val prebufferedIndices: Set<Int> = emptySet(), // Tập hợp các chỉ số câu đã nạp sẵn vào RAM
    val isSynthesizing: Boolean = false            // Đang tính toán suy luận ONNX
)
