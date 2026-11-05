package com.novelr.reader.service.piper

data class PiperVoiceProfile(
    val id: String,
    val name: String,
    val gender: String,
    val description: String,
    val isVivosMultiSpeaker: Boolean = false,
    val speakerId: Int = 0,
    val formantShift: Float = 1.0f,   // Hệ số dịch tần số formant tạo đa dạng âm sắc Nam/Nữ/Trầm/Thanh mà vẫn rõ chữ
    val noiseScale: Float = 0.667f,
    val noiseW: Float = 0.8f
)

object PiperConstants {
    const val OUTPUT_SAMPLE_RATE = 24000

    // 10 Giọng chuẩn HD (dựa trên VAIS-1000 Medium 22.05kHz - đầy đủ 154 token dấu thanh, đọc cực rõ chữ)
    val HD_PRESET_VOICES = listOf(
        PiperVoiceProfile(
            id = "vais_default",
            name = "Thanh Mai (Chuẩn HD)",
            gender = "Nữ • Bắc",
            description = "Phát thanh viên VAIS-1000 gốc • Đọc rõ từng chữ, chuẩn dấu 100%",
            isVivosMultiSpeaker = false,
            formantShift = 1.0f,
            noiseScale = 0.55f,
            noiseW = 0.7f
        ),
        PiperVoiceProfile(
            id = "vais_female_warm",
            name = "Thu Hà (Nữ Dịu Êm)",
            gender = "Nữ • Ấm",
            description = "Âm sắc ấm áp, tròn vành rõ chữ, nghe lâu không mỏi tai",
            isVivosMultiSpeaker = false,
            formantShift = 0.94f,
            noiseScale = 0.52f,
            noiseW = 0.68f
        ),
        PiperVoiceProfile(
            id = "vais_male_narrator",
            name = "Minh Quân (Nam Kể Chuyện)",
            gender = "Nam • Trầm vừa",
            description = "Giọng nam điềm đạm, phát âm dứt khoát, hợp đọc tiểu thuyết dài",
            isVivosMultiSpeaker = false,
            formantShift = 0.83f,
            noiseScale = 0.50f,
            noiseW = 0.65f
        ),
        PiperVoiceProfile(
            id = "vais_male_deep",
            name = "Hoàng Sơn (Nam Trầm Sâu)",
            gender = "Nam • Trầm",
            description = "Giọng nam trầm dày, rõ chữ, hợp truyện tiên hiệp / kiếm hiệp / lịch sử",
            isVivosMultiSpeaker = false,
            formantShift = 0.77f,
            noiseScale = 0.48f,
            noiseW = 0.62f
        ),
        PiperVoiceProfile(
            id = "vais_female_bright",
            name = "Ngọc Ánh (Nữ Trong Trẻo)",
            gender = "Nữ • Thanh",
            description = "Giọng nữ trẻ trung, sáng rõ, tốc độ nhanh vẫn không dính chữ",
            isVivosMultiSpeaker = false,
            formantShift = 1.06f,
            noiseScale = 0.55f,
            noiseW = 0.70f
        ),
        PiperVoiceProfile(
            id = "vais_male_news",
            name = "Tuấn Kiệt (Nam Phát Thanh)",
            gender = "Nam • Rõ nét",
            description = "Giọng nam trung tính, nhịp điệu đều đặn, chuẩn đọc sách kiến thức",
            isVivosMultiSpeaker = false,
            formantShift = 0.86f,
            noiseScale = 0.45f,
            noiseW = 0.60f
        ),
        PiperVoiceProfile(
            id = "vais_night_whisper",
            name = "Lan Hương (Đọc Đêm Khuya)",
            gender = "Nữ • Trầm dịu",
            description = "Giọng đọc nhẹ nhàng, giảm độ gắt, tối ưu nghe trước khi ngủ",
            isVivosMultiSpeaker = false,
            formantShift = 0.91f,
            noiseScale = 0.42f,
            noiseW = 0.58f
        ),
        PiperVoiceProfile(
            id = "vais_elder",
            name = "Bác Ba (Lão Thành)",
            gender = "Nam • Lão niên",
            description = "Giọng trầm cổ điển, nhịp đọc khoan thai, rõ ràng",
            isVivosMultiSpeaker = false,
            formantShift = 0.74f,
            noiseScale = 0.52f,
            noiseW = 0.66f
        )
    )

    /**
     * Lấy thông tin cấu hình giọng Piper từ ID (hỗ trợ cả 8 giọng HD và 65 giọng VIVOS `vivos_0`..`vivos_64`).
     */
    fun getVoiceProfile(voiceId: String): PiperVoiceProfile {
        HD_PRESET_VOICES.find { it.id == voiceId }?.let { return it }

        if (voiceId.startsWith("vivos_")) {
            val spk = voiceId.removePrefix("vivos_").toIntOrNull()?.coerceIn(0, 64) ?: 0
            val numLabel = String.format("%02d", spk + 1)
            return PiperVoiceProfile(
                id = "vivos_$spk",
                name = "VIVOS #$numLabel",
                gender = "Giọng #${spk + 1}/65",
                description = "Giọng thu âm thực tế số ${spk + 1} trong bộ ngữ liệu VIVOS (65 người đọc)",
                isVivosMultiSpeaker = true,
                speakerId = spk,
                formantShift = 1.0f,
                noiseScale = 0.667f,
                noiseW = 0.8f
            )
        }

        return HD_PRESET_VOICES.first()
    }
}
