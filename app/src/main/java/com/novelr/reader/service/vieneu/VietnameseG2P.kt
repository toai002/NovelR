package com.novelr.reader.service.vieneu

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * Bộ chuyển đổi Grapheme-to-Phoneme (G2P) Tiếng Việt chuẩn IPA cho VieNeu-TTS v3 Nano.
 * Sử dụng từ điển 25.406 âm tiết/từ ghép trích xuất từ `sea_g2p` kết hợp bộ quy tắc âm vị học
 * tiếng Việt (Onset + Rhyme + Tone) và đọc số tự động.
 */
class VietnameseG2P(private val context: Context) {

    companion object {
        private const val TAG = "VietnameseG2P"
        private const val DICT_ASSET_PATH = "vieneu/vi_g2p.tsv"

        private val PUNCTUATION_SET = setOf(',', '.', '!', '?', ';', ':')

        private val DIGIT_WORDS = arrayOf(
            "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
        )

        private val EMOTION_REPLACEMENTS = listOf(
            "[cười]" to "<|emotion_1|>",
            "[laugh]" to "<|emotion_1|>",
            "[chuckle]" to "<|emotion_1|>",
            "[thở dài]" to "<|emotion_2|>",
            "[sigh]" to "<|emotion_2|>",
            "[gasp]" to "<|emotion_2|>",
            "[hắng giọng]" to "<|emotion_3|>",
            "[clear throat]" to "<|emotion_3|>",
            "[cough]" to "<|emotion_3|>"
        )

        // Ánh xạ thanh điệu trên nguyên âm tiếng Việt -> số thanh sea_g2p (1: ngang, 2: huyền, 3: sắc, 4: hỏi, 5: ngã, 6: nặng)
        // Lưu ý: thanh ngang (1) không ghi số trong chuỗi IPA của sea_g2p
        private val VOWEL_TONE_MAP = mapOf(
            'à' to ('a' to "2"), 'á' to ('a' to "3"), 'ả' to ('a' to "4"), 'ã' to ('a' to "5"), 'ạ' to ('a' to "6"),
            'ằ' to ('ă' to "2"), 'ắ' to ('ă' to "3"), 'ẳ' to ('ă' to "4"), 'ẵ' to ('ă' to "5"), 'ặ' to ('ă' to "6"),
            'ầ' to ('â' to "2"), 'ấ' to ('â' to "3"), 'ẩ' to ('â' to "4"), 'ẫ' to ('â' to "5"), 'ậ' to ('â' to "6"),
            'è' to ('e' to "2"), 'é' to ('e' to "3"), 'ẻ' to ('e' to "4"), 'ẽ' to ('e' to "5"), 'ẹ' to ('e' to "6"),
            'ề' to ('ê' to "2"), 'ế' to ('ê' to "3"), 'ể' to ('ê' to "4"), 'ễ' to ('ê' to "5"), 'ệ' to ('ê' to "6"),
            'ì' to ('i' to "2"), 'í' to ('i' to "3"), 'ỉ' to ('i' to "4"), 'ĩ' to ('i' to "5"), 'ị' to ('i' to "6"),
            'ò' to ('o' to "2"), 'ó' to ('o' to "3"), 'ỏ' to ('o' to "4"), 'õ' to ('o' to "5"), 'ọ' to ('o' to "6"),
            'ồ' to ('ô' to "2"), 'ố' to ('ô' to "3"), 'ổ' to ('ô' to "4"), 'ỗ' to ('ô' to "5"), 'ộ' to ('ô' to "6"),
            'ờ' to ('ơ' to "2"), 'ớ' to ('ơ' to "3"), 'ở' to ('ơ' to "4"), 'ỡ' to ('ơ' to "5"), 'ợ' to ('ơ' to "6"),
            'ù' to ('u' to "2"), 'ú' to ('u' to "3"), 'ủ' to ('u' to "4"), 'ũ' to ('u' to "5"), 'ụ' to ('u' to "6"),
            'ừ' to ('ư' to "2"), 'ứ' to ('ư' to "3"), 'ử' to ('ư' to "4"), 'ữ' to ('ư' to "5"), 'ự' to ('ư' to "6"),
            'ỳ' to ('y' to "2"), 'ý' to ('y' to "3"), 'ỷ' to ('y' to "4"), 'ỹ' to ('y' to "5"), 'ỵ' to ('y' to "6")
        )

        private val ONSET_RULES = listOf(
            "ngh" to "ŋ",
            "ng" to "ŋ",
            "nh" to "ɲ",
            "ch" to "tɕ",
            "gh" to "ɣ",
            "gi" to "z",
            "kh" to "x",
            "ph" to "f",
            "qu" to "kw",
            "th" to "tʰ",
            "tr" to "ʈ",
            "đ" to "d",
            "d" to "z",
            "r" to "ʐ",
            "s" to "s",
            "x" to "s",
            "v" to "v",
            "b" to "b",
            "c" to "k",
            "k" to "k",
            "g" to "ɣ",
            "h" to "h",
            "l" to "l",
            "m" to "m",
            "n" to "n",
            "p" to "p",
            "t" to "t"
        )

        private val VOWEL_IPA = mapOf(
            "iê" to "iə", "yê" to "iə", "ia" to "iə", "ya" to "iə",
            "ươ" to "ɯə", "ưa" to "ɯə",
            "uô" to "uə", "ua" to "uə",
            "oo" to "ɔː",
            "a" to "aː",
            "ă" to "a",
            "â" to "ə",
            "e" to "ɛ",
            "ê" to "e",
            "i" to "i",
            "y" to "i",
            "o" to "ɔ",
            "ô" to "o",
            "ơ" to "əː",
            "u" to "u",
            "ư" to "ɯ"
        )

        private val CODA_IPA = listOf(
            "ng" to "ŋ",
            "nh" to "ɲ",
            "ch" to "k",
            "m" to "m",
            "n" to "n",
            "p" to "p",
            "t" to "t",
            "c" to "k",
            "i" to "j",
            "y" to "j",
            "o" to "w",
            "u" to "w"
        )
    }

    @Volatile
    private var dictionary: HashMap<String, String>? = null

    private fun getOrLoadDictionary(): HashMap<String, String> {
        dictionary?.let { return it }
        synchronized(this) {
            dictionary?.let { return it }
            val map = HashMap<String, String>(32768)
            try {
                context.assets.open(DICT_ASSET_PATH).use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            val tabIdx = line.indexOf('\t')
                            if (tabIdx > 0 && tabIdx < line.length - 1) {
                                val word = line.substring(0, tabIdx)
                                val ph = line.substring(tabIdx + 1)
                                map[word] = ph
                            }
                        }
                    }
                }
                Log.i(TAG, "Đã nạp từ điển G2P tiếng Việt: ${map.size} mục từ")
            } catch (e: Exception) {
                Log.e(TAG, "Không thể nạp $DICT_ASSET_PATH: ${e.message}", e)
            }
            dictionary = map
            return map
        }
    }

    /**
     * Chuyển chuỗi văn bản tiếng Việt sang chuỗi âm vị IPA chuẩn `sea_g2p` cho VieNeu-TTS v3 Nano.
     */
    fun textToPhonemes(rawText: String): String {
        if (rawText.isBlank()) return ""
        val dict = getOrLoadDictionary()

        // 1. Chuẩn hóa Unicode NFC và thay thế các thẻ cảm xúc
        var text = Normalizer.normalize(rawText.trim(), Normalizer.Form.NFC)
        for ((tag, token) in EMOTION_REPLACEMENTS) {
            text = text.replace(tag, " $token ", ignoreCase = true)
        }

        // 2. Chuyển đổi số thành chữ tiếng Việt (ví dụ: "2026" -> "hai nghìn không trăm hai mươi sáu")
        text = expandNumbersInText(text)

        // 3. Chuẩn hóa dấu câu phổ biến
        text = text
            .replace("…", ".")
            .replace("...", ".")
            .replace("–", ",")
            .replace("—", ",")
            .replace("-", " ")
            .replace("\n", " . ")

        // 4. Tách thành danh sách token (từ, dấu câu hoặc emotion tag)
        val rawTokens = tokenizeWordsAndPunct(text)
        if (rawTokens.isEmpty()) return ""

        val resultParts = ArrayList<String>(rawTokens.size)
        var i = 0
        while (i < rawTokens.size) {
            val current = rawTokens[i]

            // Nếu là thẻ emotion đặc biệt
            if (current.startsWith("<|emotion_") && current.endsWith("|>")) {
                resultParts.add(current)
                i++
                continue
            }

            // Nếu là dấu câu đơn lẻ: gắn trực tiếp vào âm vị của từ ngay trước đó (giống sea_g2p)
            if (current.length == 1 && current[0] in PUNCTUATION_SET) {
                val punct = if (current[0] == ';' || current[0] == ':') ',' else current[0]
                if (resultParts.isNotEmpty()) {
                    val lastIdx = resultParts.lastIndex
                    val prev = resultParts[lastIdx]
                    if (!prev.endsWith(punct)) {
                        resultParts[lastIdx] = prev + punct
                    }
                }
                i++
                continue
            }

            val lowerWord = current.lowercase()

            // Thử ghép từ đôi (2-gram) trong từ điển sea_g2p nếu từ tiếp theo là chữ
            if (i + 1 < rawTokens.size) {
                val nextToken = rawTokens[i + 1]
                if (!nextToken.startsWith("<|") && !(nextToken.length == 1 && nextToken[0] in PUNCTUATION_SET)) {
                    val bigram = "${lowerWord}_${nextToken.lowercase()}"
                    val bigramPh = dict[bigram]
                    if (bigramPh != null) {
                        resultParts.add(bigramPh)
                        i += 2
                        continue
                    }
                }
            }

            // Tra từ đơn trong từ điển hoặc suy luận theo quy tắc âm vị tiếng Việt
            val phoneme = dict[lowerWord] ?: fallbackSyllableToIpa(lowerWord, dict)
            if (phoneme.isNotBlank()) {
                resultParts.add(phoneme)
            }
            i++
        }

        return resultParts.joinToString(" ")
    }

    private fun tokenizeWordsAndPunct(text: String): List<String> {
        val tokens = ArrayList<String>()
        val regex = Regex("""<\|emotion_\d+\|>|[\p{L}\p{M}]+|[.,!?;:]""")
        for (match in regex.findAll(text)) {
            val value = match.value.trim()
            if (value.isNotEmpty()) {
                tokens.add(value)
            }
        }
        return tokens
    }

    internal fun expandNumbersInText(text: String): String {
        val numberRegex = Regex("""\b\d{1,9}\b""")
        return numberRegex.replace(text) { matchResult ->
            val num = matchResult.value.toLongOrNull()
            if (num != null) numberToVietnameseWords(num) else matchResult.value
        }
    }

    internal fun numberToVietnameseWords(n: Long): String {
        if (n == 0L) return "không"
        if (n < 0L) return "âm " + numberToVietnameseWords(-n)

        val parts = ArrayList<String>()
        val millions = (n / 1_000_000L).toInt()
        val thousands = ((n % 1_000_000L) / 1_000L).toInt()
        val remainder = (n % 1_000L).toInt()

        if (millions > 0) {
            parts.add(readThreeDigits(millions, fullHundred = false) + " triệu")
        }
        if (thousands > 0) {
            parts.add(readThreeDigits(thousands, fullHundred = millions > 0) + " nghìn")
        }
        if (remainder > 0) {
            parts.add(readThreeDigits(remainder, fullHundred = millions > 0 || thousands > 0))
        }
        return parts.joinToString(" ")
    }

    private fun readThreeDigits(num: Int, fullHundred: Boolean): String {
        val h = num / 100
        val t = (num % 100) / 10
        val u = num % 10
        val words = ArrayList<String>()

        if (h > 0 || fullHundred) {
            words.add("${DIGIT_WORDS[h]} trăm")
        }
        if (t == 0) {
            if (u > 0) {
                if (h > 0 || fullHundred) {
                    words.add("lẻ")
                }
                words.add(DIGIT_WORDS[u])
            }
        } else if (t == 1) {
            words.add("mười")
            if (u == 5) {
                words.add("lăm")
            } else if (u > 0) {
                words.add(DIGIT_WORDS[u])
            }
        } else {
            words.add("${DIGIT_WORDS[t]} mươi")
            when (u) {
                1 -> words.add("mốt")
                4 -> words.add("tư")
                5 -> words.add("lăm")
                in 2..9 -> words.add(DIGIT_WORDS[u])
            }
        }
        return words.joinToString(" ")
    }

    /**
     * Suy luận âm vị dự phòng cho các từ hiếm/tên riêng không có trong 25.406 từ của `vi_g2p.tsv`.
     */
    private fun fallbackSyllableToIpa(word: String, dict: Map<String, String>): String {
        if (word.isEmpty()) return ""

        // Tách dấu thanh điệu ra khỏi nguyên âm
        var tone = ""
        val baseBuilder = StringBuilder(word.length)
        for (ch in word) {
            val mapped = VOWEL_TONE_MAP[ch]
            if (mapped != null) {
                baseBuilder.append(mapped.first)
                tone = mapped.second
            } else {
                baseBuilder.append(ch)
            }
        }
        val baseWord = baseBuilder.toString()

        // Thử tra từ gốc không dấu trong từ điển rồi gắn thanh điệu
        dict[baseWord]?.let { basePh ->
            return if (tone.isEmpty()) basePh else basePh + tone
        }

        // Quy tắc tách Onset + Nucleus + Coda
        var rest = baseWord
        var onsetIpa = ""
        for ((prefix, ipa) in ONSET_RULES) {
            if (rest.startsWith(prefix) && rest.length > prefix.length) {
                onsetIpa = ipa
                rest = rest.substring(prefix.length)
                break
            }
        }

        var codaIpa = ""
        for ((suffix, ipa) in CODA_IPA) {
            if (rest.endsWith(suffix) && rest.length > suffix.length) {
                codaIpa = ipa
                rest = rest.substring(0, rest.length - suffix.length)
                break
            }
        }

        val nucleusIpa = VOWEL_IPA[rest] ?: rest.map { VOWEL_IPA[it.toString()] ?: it.toString() }.joinToString("")
        return "$onsetIpaˈ$nucleusIpa$tone$codaIpa"
    }
}
