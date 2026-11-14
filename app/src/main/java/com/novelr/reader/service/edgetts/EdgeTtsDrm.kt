package com.novelr.reader.service.edgetts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor

/**
 * Triển khai chuẩn thuật toán tạo mã bảo mật `Sec-MS-GEC` v2 (CS64 + Salted SHA-256)
 * của Microsoft Edge 143 (`2-143.0.3650.75`) kèm cơ chế tự động đồng bộ độ lệch thời gian (Clock Skew).
 */
object EdgeTtsDrm {
    private const val WIN_EPOCH = 11644473600L
    private const val U32_MASK = 0xFFFFFFFFL

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
    )

    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
    )

    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L
    )

    private val SHA256_ROUND_CONSTANTS = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
        0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
        0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
        0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
        0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L
    )

    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private val SHA256_BLOCK_CONSTANTS = LongArray(64) { i ->
        if (i == 14) SHA256_ADJUSTMENT else SHA256_ROUND_CONSTANTS[i]
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    private val CS64_COEFFICIENTS = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L),
        longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L),
        longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L),
        longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L),
        longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL),
        longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L),
        longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L),
        longArrayOf(0xCB49E3C6L, 0xC5626167L)
    )

    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    @Volatile
    private var clockSkewSeconds: Double = 0.0

    private val secureRandom = SecureRandom()

    fun generateMuid(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytesToHexUpper(bytes)
    }

    fun generateConnectId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun adjustClockSkewFromServerDate(serverDateStr: String?) {
        if (serverDateStr.isNullOrBlank()) return
        try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val serverEpochSec = (sdf.parse(serverDateStr)?.time ?: return) / 1000.0
            val localEpochSec = System.currentTimeMillis() / 1000.0
            val rawSkew = serverEpochSec - localEpochSec
            // Giới hạn độ lệch đồng hồ tối đa ±24 giờ để tránh bị đầu độc bởi Date header bất thường
            clockSkewSeconds = rawSkew.coerceIn(-86_400.0, 86_400.0)
        } catch (_: Exception) {
        }
    }

    fun generateSecMsGecV2(unixTimestampSec: Double? = null): String {
        val baseSec = unixTimestampSec ?: ((System.currentTimeMillis() / 1000.0) + clockSkewSeconds)
        var ticks = floor(baseSec).toLong() + WIN_EPOCH
        ticks -= (ticks % 300L)
        val ticks100Ns = ticks * 10_000_000L
        val strToHash = "$ticks100Ns${EdgeTtsConstants.TRUSTED_CLIENT_TOKEN}"
        val asciiBytes = strToHash.toByteArray(Charsets.US_ASCII)

        val digest = sha256Digest(asciiBytes)
        val (csWord0, csWord1) = cs64Digest(asciiBytes, digest)
        val csBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(csWord0.toInt())
            .putInt(csWord1.toInt())
            .array()

        return "${bytesToHexUpper(csBytes)}-${bytesToHexUpper(digest)}"
    }

    private fun rol16(value: Long): Long {
        val v = value and U32_MASK
        return ((v shl 16) or (v ushr 16)) and U32_MASK
    }

    private fun rotr32(value: Long, bits: Int): Long {
        val v = value and U32_MASK
        return ((v ushr bits) or (v shl (32 - bits))) and U32_MASK
    }

    private fun sigma0(value: Long): Long {
        val v = value and U32_MASK
        return (rotr32(v, 7) xor rotr32(v, 18) xor (v ushr 3)) and U32_MASK
    }

    private fun sigma1(value: Long): Long {
        val v = value and U32_MASK
        return (rotr32(v, 17) xor rotr32(v, 19) xor (v ushr 10)) and U32_MASK
    }

    private fun bigSigma0(a: Long): Long {
        val v = a and U32_MASK
        return (rotr32(v, 2) xor rotr32(v, 13) xor rotr32(v, 22)) and U32_MASK
    }

    private fun bigSigma1(e: Long): Long {
        val v = e and U32_MASK
        return (rotr32(v, 6) xor rotr32(v, 11) xor rotr32(v, 25)) and U32_MASK
    }

    private fun sha256Rounds(state: LongArray, words: LongArray, firstRound: Int = 0): LongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        for (index in firstRound until 64) {
            val ch = g xor (e and (f xor g))
            val maj = (a and b) or (c and (a or b))
            val t1 = (h + bigSigma1(e) + ch + constants[index] + words[index]) and U32_MASK
            val t2 = (bigSigma0(a) + maj) and U32_MASK

            h = g
            g = f
            f = e
            e = (d + t1) and U32_MASK
            d = c
            c = b
            b = a
            a = (t1 + t2) and U32_MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSha256Schedule(words: LongArray, firstWord: Int = 16) {
        for (index in firstWord until 64) {
            val s1 = sigma1(words[index - 2])
            val s0 = sigma0(words[index - 15])
            words[index] = (s1 + words[index - 7] + s0 + words[index - 16]) and U32_MASK
        }
    }

    private fun sha256Init(data7: ByteArray): LongArray {
        val words = LongArray(64)
        words[14] = (((data7[0].toLong() and 0xFF) shl 16) or
                ((data7[1].toLong() and 0xFF) shl 8) or
                (data7[2].toLong() and 0xFF)) and U32_MASK
        words[15] = (((data7[3].toLong() and 0xFF) shl 24) or
                ((data7[4].toLong() and 0xFF) shl 16) or
                ((data7[5].toLong() and 0xFF) shl 8) or
                (data7[6].toLong() and 0xFF)) and U32_MASK

        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    sigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) {
                value += words[index - 7]
            }
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    sigma0(words[index - 15])
                }
            }
            words[index] = value and U32_MASK
        }

        words[30] = (
                sigma1(words[28]) +
                        words[23] +
                        sigma0(words[15]) +
                        words[14] +
                        SHA256_ADJUSTMENT -
                        SHA256_ROUND_CONSTANTS[14]
                ) and U32_MASK

        expandSha256Schedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, 14)
        return LongArray(8) { i -> (SHA256_INITIAL[i] + working[i]) and U32_MASK }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val tailLen = data.size - 7
        val zeroCount = ((55 - length) % 64 + 64) % 64
        val padded = ByteBuffer.allocate(tailLen + 1 + zeroCount + 8).order(ByteOrder.BIG_ENDIAN)
        padded.put(data, 7, tailLen)
        padded.put(0x80.toByte())
        repeat(zeroCount) { padded.put(0.toByte()) }
        padded.putLong((length * 8).toLong())
        val paddedBytes = padded.array()

        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < paddedBytes.size) {
            val words = LongArray(64)
            val blockBuf = ByteBuffer.wrap(paddedBytes, offset, 64).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until 16) {
                words[i] = blockBuf.int.toLong() and U32_MASK
            }
            expandSha256Schedule(words, 16)
            val working = sha256Rounds(state, words, 0)
            state = LongArray(8) { i -> (state[i] + working[i]) and U32_MASK }
            offset += 64
        }

        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        for (w in state) {
            out.putInt(w.toInt())
        }
        return out.array()
    }

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val rem = ((-data.size) % 4 + 4) % 4
        val paddedLen = data.size + rem
        if (paddedLen == 0) return LongArray(0)
        val padded = ByteArray(paddedLen)
        System.arraycopy(data, 0, padded, 0, data.size)

        val wordCount = paddedLen / 4
        val evenCount = if (wordCount % 2 != 0) wordCount + 1 else wordCount
        val out = LongArray(evenCount)
        val buf = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until wordCount) {
            out[i] = buf.int.toLong() and U32_MASK
        }
        return out
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val digestBuf = ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = (digestBuf.int.toLong() and U32_MASK) or 1L
        val keyB = (digestBuf.int.toLong() and U32_MASK) or 1L

        val factor0 = (keyA * CS64_FACTOR) and U32_MASK
        val factor1 = (keyB * CS64_FACTOR) and U32_MASK

        val terms = ArrayList<Long>(32)
        val offsets = ArrayList<Long>(32)

        for (index in CS64_COEFFICIENTS.indices) {
            val coeff = CS64_COEFFICIENTS[index][0]
            val off = CS64_COEFFICIENTS[index][1]
            val f = if (index % 2 == 0) factor0 else factor1
            terms.add((coeff * f) and U32_MASK)
            offsets.add(off)
        }

        val prefixed = ByteArray(1 + data.size)
        System.arraycopy(data, 0, prefixed, 1, data.size)
        val words = wordsLe4Even(prefixed)

        for (index in words.indices) {
            val word = words[index]
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factor0
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and U32_MASK)
            offsets.add(offset and U32_MASK)
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L

        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]

            var t = rol16((offsets[index] + x) and U32_MASK)
            var q = (t * 0x76F50F34L) and U32_MASK
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and U32_MASK

            t = rol16((offsets[index + 1] + y) and U32_MASK)
            q = (t * 0x1881CE3CL) and U32_MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and U32_MASK
            swapAcc = (swapAcc + swapLast) and U32_MASK

            state = (state * keyA + x) and U32_MASK
            state = (rol16(state) * 0xCF98B111L) and U32_MASK
            state = (rol16(state) * 0x87085B9FL) and U32_MASK
            state = (rol16(state) * 0x12CEB96DL) and U32_MASK
            state = (rol16(state) * 0x257E1D83L) and U32_MASK
            acc += state

            t = (state * keyB + y) and U32_MASK
            t = (rol16(t) * 0xA27416F5L) and U32_MASK
            t = (rol16(t) * 0xD38396FFL) and U32_MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and U32_MASK) * 0xBFA49F69L) and U32_MASK
            acc = (acc + state) and U32_MASK

            index += 2
        }

        return Pair((swapLast xor state) and U32_MASK, (swapAcc xor acc) and U32_MASK)
    }

    private fun bytesToHexUpper(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("%02X".format(b))
        }
        return sb.toString()
    }
}
