package com.novelr.reader.service.edgetts

data class EdgeVoiceProfile(
    val id: String,
    val shortName: String,
    val name: String,
    val gender: String,
    val description: String
)

object EdgeTtsConstants {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
    const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    const val VOICE_LIST_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    const val DEFAULT_EDGE_VOICE = "en-US-AvaMultilingualNeural"

    val PRESET_VOICES = listOf(
        EdgeVoiceProfile(
            id = "en-US-AvaMultilingualNeural",
            shortName = "en-US-AvaMultilingualNeural",
            name = "Ava Đa Ngôn Ngữ (Siêu tốc)",
            gender = "Nữ • Việt-Anh • Khuyên dùng",
            description = "Giọng nữ đa ngôn ngữ phản hồi siêu nhanh (~0.2s) • Đọc tiếng Việt & tên tiếng Anh cực mượt"
        ),
        EdgeVoiceProfile(
            id = "fr-FR-VivienneMultilingualNeural",
            shortName = "fr-FR-VivienneMultilingualNeural",
            name = "Vivienne Đa Ngôn Ngữ (Siêu tốc)",
            gender = "Nữ • Thanh lịch • Nhanh",
            description = "Giọng nữ đa ngôn ngữ tốc độ cao, âm sắc sang trọng, đọc rõ từng chữ"
        ),
        EdgeVoiceProfile(
            id = "fr-FR-RemyMultilingualNeural",
            shortName = "fr-FR-RemyMultilingualNeural",
            name = "Remy Đa Ngôn Ngữ (Siêu tốc)",
            gender = "Nam • Trầm sâu • Khuyên dùng",
            description = "Giọng nam đa ngôn ngữ phản hồi nhanh, trầm dày, hợp đọc truyện kiếm hiệp / tiên hiệp"
        ),
        EdgeVoiceProfile(
            id = "en-US-EmmaMultilingualNeural",
            shortName = "en-US-EmmaMultilingualNeural",
            name = "Emma Đa Ngôn Ngữ",
            gender = "Nữ • Dịu êm",
            description = "Giọng nữ đa ngôn ngữ nhẹ nhàng, truyền cảm, nghe lâu không mỏi tai"
        ),
        EdgeVoiceProfile(
            id = "en-US-BrianMultilingualNeural",
            shortName = "en-US-BrianMultilingualNeural",
            name = "Brian Đa Ngôn Ngữ",
            gender = "Nam • Sáng rõ",
            description = "Giọng nam đa ngôn ngữ trẻ trung, phát âm dứt khoát, tốc độ phản hồi nhanh"
        ),
        EdgeVoiceProfile(
            id = "en-US-AndrewMultilingualNeural",
            shortName = "en-US-AndrewMultilingualNeural",
            name = "Andrew Đa Ngôn Ngữ",
            gender = "Nam • Việt-Anh",
            description = "Giọng nam đa ngôn ngữ ấm áp • Đọc tiểu thuyết có tên nước ngoài cực chuẩn"
        ),
        EdgeVoiceProfile(
            id = "vi-VN-HoaiMyNeural",
            shortName = "vi-VN-HoaiMyNeural",
            name = "Hoài My (Chuẩn Edge)",
            gender = "Nữ • Tiếng Việt",
            description = "Giọng nữ Tiếng Việt chính thức của Microsoft Edge • Tự động tăng tốc dự phòng khi nghẽn"
        ),
        EdgeVoiceProfile(
            id = "vi-VN-NamMinhNeural",
            shortName = "vi-VN-NamMinhNeural",
            name = "Nam Minh (Chuẩn Edge)",
            gender = "Nam • Tiếng Việt",
            description = "Giọng nam Tiếng Việt chính thức của Microsoft Edge • Tự động tăng tốc dự phòng khi nghẽn"
        )
    )

    fun findById(voiceId: String): EdgeVoiceProfile {
        return PRESET_VOICES.find { it.id == voiceId } ?: PRESET_VOICES.first()
    }

    /**
     * Chuyển mã ngắn (vd: "en-US-AvaMultilingualNeural") sang định dạng chuẩn của Microsoft Edge Server:
     * "Microsoft Server Speech Text to Speech Voice (en-US, AvaMultilingualNeural)"
     */
    fun toServerVoiceName(shortName: String): String {
        val parts = shortName.split("-")
        return if (parts.size >= 3) {
            val langRegion = "${parts[0]}-${parts[1]}"
            val voiceName = parts.drop(2).joinToString("-")
            "Microsoft Server Speech Text to Speech Voice ($langRegion, $voiceName)"
        } else {
            shortName
        }
    }
}
