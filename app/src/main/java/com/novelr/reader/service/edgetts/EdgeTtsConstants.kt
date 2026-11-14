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

    const val DEFAULT_EDGE_VOICE = "vi-VN-HoaiMyNeural"

    /**
     * Danh sách giọng đọc Microsoft Edge TTS thuần Tiếng Việt (vi-VN) thống nhất 100%,
     * tuyệt đối không pha trộn giọng đa ngôn ngữ tiếng Anh.
     */
    val PRESET_VOICES = listOf(
        EdgeVoiceProfile(
            id = "vi-VN-HoaiMyNeural",
            shortName = "vi-VN-HoaiMyNeural",
            name = "Hoài My (Nữ • Chuẩn Tiếng Việt)",
            gender = "Nữ • Tiếng Việt chuẩn Edge",
            description = "Giọng nữ Tiếng Việt chính thức của Microsoft Edge • Đọc rõ chữ, tự nhiên, thống nhất 100% âm sắc Việt"
        ),
        EdgeVoiceProfile(
            id = "vi-VN-NamMinhNeural",
            shortName = "vi-VN-NamMinhNeural",
            name = "Nam Minh (Nam • Chuẩn Tiếng Việt)",
            gender = "Nam • Tiếng Việt chuẩn Edge",
            description = "Giọng nam Tiếng Việt chính thức của Microsoft Edge • Trầm ấm, đĩnh đạc, thống nhất 100% âm sắc Việt"
        )
    )

    fun findById(voiceId: String): EdgeVoiceProfile {
        return PRESET_VOICES.find { it.id == voiceId } ?: PRESET_VOICES.first()
    }

    /**
     * Chuyển mã ngắn (vd: "vi-VN-HoaiMyNeural") sang định dạng chuẩn của Microsoft Edge Server:
     * "Microsoft Server Speech Text to Speech Voice (vi-VN, HoaiMyNeural)"
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
