package com.novelr.reader.service.edgetts

data class EdgeVoiceProfile(
    val id: String,
    val shortName: String,
    val name: String,
    val gender: String,
    val description: String,
    val isMultilingualFast: Boolean = true
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

    /**
     * Danh sách giọng đọc Microsoft Edge TTS:
     * - 6 giọng Đa ngôn ngữ Siêu tốc (~120–250ms/câu nhờ tái sử dụng Warm WebSocket Pool + Chuẩn hóa âm vị Tiếng Việt 100%).
     * - 2 giọng Tiếng Việt truyền thống (Hoài My & Nam Minh).
     * Toàn bộ đều khóa cố định 1 giọng duy nhất suốt truyện (không bao giờ tự nhảy sang giọng khác).
     */
    val PRESET_VOICES = listOf(
        EdgeVoiceProfile(
            id = "en-US-AvaMultilingualNeural",
            shortName = "en-US-AvaMultilingualNeural",
            name = "Ava Đa Ngôn Ngữ (Nữ • Siêu tốc 150ms)",
            gender = "Nữ • Siêu tốc • Chuẩn hóa Tiếng Việt",
            description = "Giọng nữ AI thế hệ mới phản hồi tức thì (~150ms) • Đã khóa phát âm Tiếng Việt cho từ cảm thán (ha ha, ồ...)",
            isMultilingualFast = true
        ),
        EdgeVoiceProfile(
            id = "en-US-AndrewMultilingualNeural",
            shortName = "en-US-AndrewMultilingualNeural",
            name = "Andrew Đa Ngôn Ngữ (Nam • Siêu tốc 150ms)",
            gender = "Nam trầm • Siêu tốc • Chuẩn hóa Tiếng Việt",
            description = "Giọng nam trầm ấm phản hồi tức thì (~150ms) • Đã khóa phát âm Tiếng Việt cho từ cảm thán (ha ha, ồ...)",
            isMultilingualFast = true
        ),
        EdgeVoiceProfile(
            id = "en-US-EmmaMultilingualNeural",
            shortName = "en-US-EmmaMultilingualNeural",
            name = "Emma Đa Ngôn Ngữ (Nữ • Dịu êm 150ms)",
            gender = "Nữ dịu • Siêu tốc • Chuẩn hóa Tiếng Việt",
            description = "Giọng nữ nhẹ nhàng, truyền cảm (~150ms) • Đã khóa phát âm Tiếng Việt, đọc truyện đêm khuya êm tai",
            isMultilingualFast = true
        ),
        EdgeVoiceProfile(
            id = "en-US-BrianMultilingualNeural",
            shortName = "en-US-BrianMultilingualNeural",
            name = "Brian Đa Ngôn Ngữ (Nam • Trẻ trung 150ms)",
            gender = "Nam sáng • Siêu tốc • Chuẩn hóa Tiếng Việt",
            description = "Giọng nam rõ chữ, nhịp điệu linh hoạt (~150ms) • Đã khóa phát âm Tiếng Việt xuyên suốt",
            isMultilingualFast = true
        ),
        EdgeVoiceProfile(
            id = "fr-FR-VivienneMultilingualNeural",
            shortName = "fr-FR-VivienneMultilingualNeural",
            name = "Vivienne Đa Ngôn Ngữ (Nữ • Trầm ấm 150ms)",
            gender = "Nữ trầm • Siêu tốc • Chuẩn hóa Tiếng Việt",
            description = "Giọng nữ trầm sâu lắng (~150ms) • Hợp đọc tiểu thuyết tâm lý, ngôn tình",
            isMultilingualFast = true
        ),
        EdgeVoiceProfile(
            id = "fr-FR-RemyMultilingualNeural",
            shortName = "fr-FR-RemyMultilingualNeural",
            name = "Remy Đa Ngôn Ngữ (Nam • Kể chuyện 150ms)",
            gender = "Nam ấm • Siêu tốc • Chuẩn hóa Tiếng Việt",
            description = "Giọng nam kể chuyện điềm tĩnh (~150ms) • Hợp đọc tiên hiệp, kiếm hiệp, lịch sử",
            isMultilingualFast = true
        ),
        EdgeVoiceProfile(
            id = "vi-VN-HoaiMyNeural",
            shortName = "vi-VN-HoaiMyNeural",
            name = "Hoài My (Nữ • Giọng Việt truyền thống)",
            gender = "Nữ • Máy chủ vi-VN",
            description = "Giọng nữ Tiếng Việt truyền thống của Microsoft Edge (tốc độ nạp phụ thuộc máy chủ vùng vi-VN)",
            isMultilingualFast = false
        ),
        EdgeVoiceProfile(
            id = "vi-VN-NamMinhNeural",
            shortName = "vi-VN-NamMinhNeural",
            name = "Nam Minh (Nam • Giọng Việt truyền thống)",
            gender = "Nam • Máy chủ vi-VN",
            description = "Giọng nam Tiếng Việt truyền thống của Microsoft Edge (tốc độ nạp phụ thuộc máy chủ vùng vi-VN)",
            isMultilingualFast = false
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
