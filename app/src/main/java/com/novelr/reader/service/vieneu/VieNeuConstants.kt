package com.novelr.reader.service.vieneu

import com.novelr.reader.model.VieNeuVoice

object VieNeuConstants {
    const val SAMPLE_RATE = 48000 // Tần số mẫu chuẩn của VieNeu và MOSS Tokenizer
    const val DEFAULT_LOOKAHEAD = 2 // Số câu render trước vào RAM

    val PRESET_VOICES = listOf(
        VieNeuVoice(
            id = "hai_dang",
            name = "Hải Đăng",
            gender = "Nam",
            description = "Giọng nam miền Bắc trầm ấm, truyền cảm, thích hợp đọc truyện kiếm hiệp, lịch sử"
        ),
        VieNeuVoice(
            id = "lan_nhi",
            name = "Lan Nhi",
            gender = "Nữ",
            description = "Giọng nữ miền Bắc dịu dàng, trong trẻo, thích hợp tiểu thuyết lãng mạn"
        ),
        VieNeuVoice(
            id = "bao_nam",
            name = "Bảo Nam",
            gender = "Nam",
            description = "Giọng nam miền Nam tự nhiên, hiện đại, thích hợp self-help, trinh thám"
        ),
        VieNeuVoice(
            id = "thao_ly",
            name = "Thảo Ly",
            gender = "Nữ",
            description = "Giọng nữ miền Nam ngọt ngào, ấm áp, thích hợp tản văn, truyện ngắn"
        )
    )
}
