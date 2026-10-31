package com.novelr.reader.service.vieneu

import com.novelr.reader.model.VieNeuVoice

object VieNeuConstants {
    const val SAMPLE_RATE = 24000 // Tần số mẫu chuẩn 24kHz của VieNeu-TTS v3 Nano (ONNX Flow-Matching)
    const val FLOW_FPS = 15.625f
    const val DEFAULT_LOOKAHEAD = 2 // Số câu render trước vào RAM

    val PRESET_VOICES = listOf(
        VieNeuVoice(
            id = "duc_tri",
            name = "Đức Trí",
            gender = "Nam · Bắc",
            description = "Phong cách đọc truyện trầm ấm, truyền cảm (VieNeu v3 Nano)"
        ),
        VieNeuVoice(
            id = "my_duyen",
            name = "Mỹ Duyên",
            gender = "Nữ · Bắc",
            description = "Phong cách đọc truyện dịu dàng, sâu lắng (VieNeu v3 Nano)"
        ),
        VieNeuVoice(
            id = "anh_khoi",
            name = "Anh Khôi",
            gender = "Nam · Bắc",
            description = "Phong cách kể chuyện tự nhiên, mạch lạc (VieNeu v3 Nano)"
        ),
        VieNeuVoice(
            id = "truc_ly",
            name = "Trúc Ly",
            gender = "Nữ · Bắc",
            description = "Giọng nữ miền Bắc tự nhiên, trong trẻo (VieNeu v3 Nano)"
        ),
        VieNeuVoice(
            id = "adam",
            name = "Adam",
            gender = "Nam · Nam",
            description = "Giọng nam miền Nam ấm áp, hiện đại (VieNeu v3 Nano)"
        ),
        VieNeuVoice(
            id = "ai_han",
            name = "Ái Hân",
            gender = "Nữ · Nam",
            description = "Giọng nữ miền Nam ngọt ngào, rõ ràng (VieNeu v3 Nano)"
        )
    )
}
