package com.novelr.reader.service.vieneu

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * Bộ chuyển đổi Grapheme-to-Phoneme (G2P) Song Ngữ Việt - Anh chuẩn IPA cho VieNeu-TTS v3 Nano:
 * 1. Từ điển 56.000 mục từ (`assets/vieneu/vi_g2p.tsv`) trích xuất từ `sea_g2p` (gồm đầy đủ 21.064 từ/cụm từ Tiếng Việt
 *    và ~35.000 tên riêng nhân vật Tiếng Anh, phương Tây, từ vựng phổ thông).
 * 2. Từ điển bổ sung các tên nhân vật tiểu thuyết, Light Novel, Anime, Fantasy phổ biến.
 * 3. Bộ quy tắc G2P Tiếng Anh / La-tinh tự động (`englishWordToIpa`) cho mọi tên nhân vật nước ngoài bất kỳ chưa có trong từ điển,
 *    đảm bảo 100% ký hiệu âm vị nằm trong `config.json` của VieNeu-TTS v3 Nano.
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

        // Từ điển tên nhân vật tiểu thuyết phương Tây, Fantasy, Light Novel, Anime phổ biến không có sẵn trong từ điển ngắn
        private val EXTRA_NOVEL_NAMES_IPA = mapOf(
            "arthur" to "ˈɑːɹθɚ",
            "harry" to "hˈæɹi",
            "potter" to "pˈɑːɾɚ",
            "hermione" to "hɜːmˈaɪəni",
            "dumbledore" to "dˈʌmbəldɔːɹ",
            "voldemort" to "vˈoʊldəmɔːɹt",
            "snape" to "snˈeɪp",
            "malfoy" to "mˈælfɔɪ",
            "weasley" to "wˈiːzli",
            "granger" to "ɡɹˈeɪndʒɚ",
            "hagrid" to "hˈæɡɹɪd",
            "sirius" to "sˈɪɹiəs",
            "remus" to "ɹˈiːməs",
            "bellatrix" to "bˈɛlətɹɪks",
            "sherlock" to "ʃˈɜːlɑːk",
            "holmes" to "hˈoʊmz",
            "watson" to "wˈɑːtsən",
            "moriarty" to "mˌɔːɹiˈɑːɹti",
            "klein" to "klˈaɪn",
            "moretti" to "mɔːɹˈɛti",
            "audrey" to "ˈɔːdɹi",
            "alger" to "ˈældʒɚ",
            "fors" to "fˈɔːɹz",
            "derrick" to "dˈɛɹɪk",
            "emlyn" to "ˈɛmlɪn",
            "cattleya" to "kˈætliə",
            "leonard" to "lˈɛnɚd",
            "xio" to "ʃˈiːoʊ",
            "amon" to "ˈeɪmən",
            "zaratul" to "zˈæɹətʊl",
            "evernight" to "ˈɛvɚnaɪt",
            "geralt" to "ɡˈɛɹəlt",
            "yennefer" to "jˈɛnɪfɚ",
            "ciri" to "sˈɪɹi",
            "triss" to "tɹˈɪs",
            "gandalf" to "ɡˈændælf",
            "frodo" to "fɹˈoʊdoʊ",
            "aragorn" to "ˈæɹəɡɔːɹn",
            "legolas" to "lˈɛɡələs",
            "sauron" to "sˈaʊɹɑːn",
            "bilbo" to "bˈɪlboʊ",
            "merlin" to "mˈɜːlɪn",
            "lancelot" to "lˈænsəlɑːt",
            "excalibur" to "ɛkskˈælɪbɚ",
            "rudeus" to "ɹˈuːdiəs",
            "greyrat" to "ɡɹˈeɪɹæt",
            "sylphiette" to "sɪlfiˈɛt",
            "sylphy" to "sˈɪlfi",
            "roxy" to "ɹˈɑːksi",
            "eris" to "ˈɛɹɪs",
            "orsted" to "ˈɔːɹstɛd",
            "rimuru" to "ɹˈɪmuːɹuː",
            "tempest" to "tˈɛmpɪst",
            "veldora" to "vɛldˈɔːɹə",
            "benimaru" to "bɛnɪmˈɑːɹuː",
            "shion" to "ʃˈiːɑːn",
            "shuna" to "ʃˈuːnə",
            "milim" to "mˈɪlɪm",
            "diablo" to "diˈɑːbloʊ",
            "ainz" to "ˈaɪnz",
            "ooal" to "ˈoʊɑːl",
            "gown" to "ɡˈaʊn",
            "albedo" to "ælbˈiːdoʊ",
            "shalltear" to "ʃˈæltɪɹ",
            "demiurge" to "dˈɛmiɜːdʒ",
            "cocytus" to "koʊsˈaɪtəs",
            "sebas" to "sˈɛbəs",
            "kirito" to "kɪɹˈiːɾoʊ",
            "asuna" to "ɐsˈuːnə",
            "eugeo" to "jˈuːdʒioʊ",
            "sinon" to "sˈiːnɑːn",
            "subaru" to "sˈuːbɑːɹuː",
            "emilia" to "ɪmˈɪliə",
            "roswaal" to "ɹˈɑːzwɑːl",
            "beatrice" to "bˈiːətɹɪs",
            "reinhard" to "ɹˈaɪnhɑːɹd",
            "tanjiro" to "tɑːndʒˈɪɹoʊ",
            "nezuko" to "nˈɛzuːkoʊ",
            "zenitsu" to "zɛnˈɪtsuː",
            "inosuke" to "ɪnˈoʊskeɪ",
            "rengoku" to "ɹɛnɡˈoʊkuː",
            "muzan" to "mˈuːzɑːn",
            "gojo" to "ɡˈoʊdʒoʊ",
            "satoru" to "sɑːtˈɔːɹuː",
            "sukuna" to "suːkˈuːnə",
            "itadori" to "iːtɑːdˈɔːɹi",
            "yuji" to "jˈuːdʒi",
            "megumi" to "mˈɛɡuːmi",
            "nobara" to "noʊbˈɑːɹə",
            "naruto" to "nɑːɹˈuːɾoʊ",
            "sasuke" to "sˈɑːskeɪ",
            "sakura" to "sɑːkˈʊɹə",
            "kakashi" to "kɑːkˈɑːʃi",
            "itachi" to "iːtˈɑːtʃi",
            "uchiha" to "uːtʃˈiːhɑː",
            "uzumaki" to "uːzuːmˈɑːki",
            "luffy" to "lˈʌfi",
            "zoro" to "zˈɔːɹoʊ",
            "sanji" to "sˈɑːndʒi",
            "nami" to "nˈɑːmi",
            "usopp" to "ˈuːsɑːp",
            "chopper" to "tʃˈɑːpɚ",
            "shanks" to "ʃˈæŋks",
            "eren" to "ˈɛɹən",
            "mikasa" to "mɪkˈɑːsə",
            "armin" to "ˈɑːɹmɪn",
            "levi" to "lˈiːvaɪ",
            "ackerman" to "ˈækɚmən"
        )

        // Ánh xạ thanh điệu trên nguyên âm tiếng Việt -> số thanh sea_g2p (2: huyền, 3: sắc, 4: hỏi, 5: ngã, 6: nặng)
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

        // Phụ âm đầu Tiếng Việt khớp 100% với bảng vocab của config.json
        private val ONSET_RULES = listOf(
            "ngh" to "ŋ",
            "ng" to "ŋ",
            "nh" to "ɲ",
            "ch" to "tʃ",
            "gh" to "ɣ",
            "gi" to "z",
            "kh" to "x",
            "ph" to "f",
            "qu" to "kw",
            "th" to "t",
            "tr" to "tʃ",
            "đ" to "ɗ",
            "d" to "z",
            "r" to "ɹ",
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

        // Nguyên âm Tiếng Việt khớp 100% với bảng vocab của config.json ('y' là âm 'ư' trong sea_g2p)
        private val VOWEL_IPA = mapOf(
            "iê" to "iə", "yê" to "iə", "ia" to "iə", "ya" to "iə",
            "ươ" to "yə", "ưa" to "yə",
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
            "ư" to "y"
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

        // Regex kiểm tra cấu trúc âm tiết thuần Việt (không dấu hoặc có dấu)
        private val VIETNAMESE_SYLLABLE_REGEX = Regex(
            """^(ngh|ng|nh|ch|gh|gi|kh|ph|qu|th|tr|b|c|d|đ|g|h|k|l|m|n|p|r|s|t|v|x)?(iê|yê|ia|ya|ươ|ưa|uô|ua|oo|oe|oa|uy|uê|uơ|ai|ao|au|ay|âu|ây|eo|êu|iu|oi|ôi|ơi|ui|ưi|ưu|uôi|ươi|oai|oay|uây|a|ă|â|e|ê|i|y|o|ô|ơ|u|ư)(ng|nh|ch|m|n|p|t|c)?$"""
        )

        private val VIETNAMESE_DIACRITIC_CHARS = (VOWEL_TONE_MAP.keys + setOf('ă', 'â', 'đ', 'ê', 'ô', 'ơ', 'ư')).toSet()
    }

    @Volatile
    private var dictionary: HashMap<String, String>? = null

    private fun getOrLoadDictionary(): HashMap<String, String> {
        dictionary?.let { return it }
        synchronized(this) {
            dictionary?.let { return it }
            val map = HashMap<String, String>(65536)
            try {
                context.assets.open(DICT_ASSET_PATH).use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            val tabIdx = line.indexOf('\t')
                            if (tabIdx > 0 && tabIdx < line.length - 1) {
                                val word = line.substring(0, tabIdx)
                                var ph = line.substring(tabIdx + 1)
                                if (ph.startsWith("<en>")) {
                                    ph = ph.substring(4)
                                }
                                map[word] = ph
                            }
                        }
                    }
                }
                Log.i(TAG, "Đã nạp từ điển G2P Việt-Anh: ${map.size} mục từ")
            } catch (e: Exception) {
                Log.e(TAG, "Không thể nạp $DICT_ASSET_PATH: ${e.message}", e)
            }
            // Bổ sung tên nhân vật tiểu thuyết ưu tiên
            for ((k, v) in EXTRA_NOVEL_NAMES_IPA) {
                if (!map.containsKey(k)) {
                    map[k] = v
                }
            }
            dictionary = map
            return map
        }
    }

    /**
     * Chuyển chuỗi văn bản Tiếng Việt (có thể pha trộn tên nhân vật Tiếng Anh / nước ngoài)
     * sang chuỗi âm vị IPA chuẩn `sea_g2p` cho VieNeu-TTS v3 Nano.
     */
    fun textToPhonemes(rawText: String): String {
        if (rawText.isBlank()) return ""
        val dict = getOrLoadDictionary()

        // 1. Chuẩn hóa Unicode NFC và thay thế các thẻ cảm xúc
        var text = Normalizer.normalize(rawText.trim(), Normalizer.Form.NFC)
        for ((tag, token) in EMOTION_REPLACEMENTS) {
            text = text.replace(tag, " $token ", ignoreCase = true)
        }

        // 2. Xử lý sở hữu cách Tiếng Anh trên tên nhân vật (ví dụ: "Arthur's" -> "Arthur 's")
        text = text.replace(Regex("""([A-Za-z]+)['’]s\b"""), "$1_possessive_s")

        // 3. Chuyển đổi số thành chữ tiếng Việt
        text = expandNumbersInText(text)

        // 4. Chuẩn hóa dấu câu phổ biến
        text = text
            .replace("…", ".")
            .replace("...", ".")
            .replace("–", ",")
            .replace("—", ",")
            .replace("-", " ")
            .replace("\n", " . ")

        // 5. Tách thành danh sách token (từ, dấu câu hoặc emotion tag)
        val rawTokens = tokenizeWordsAndPunct(text)
        if (rawTokens.isEmpty()) return ""

        val resultParts = ArrayList<String>(rawTokens.size)
        var i = 0
        while (i < rawTokens.size) {
            val current = rawTokens[i]

            if (current.startsWith("<|emotion_") && current.endsWith("|>")) {
                resultParts.add(current)
                i++
                continue
            }

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

            val isPossessive = current.endsWith("_possessive_s")
            val cleanToken = if (isPossessive) current.removeSuffix("_possessive_s") else current
            val lowerWord = cleanToken.lowercase()

            // Thử ghép từ đôi (2-gram) Tiếng Việt trong từ điển nếu từ tiếp theo là chữ
            if (!isPossessive && i + 1 < rawTokens.size) {
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

            val basePhoneme = resolveSingleWordIpa(lowerWord, dict)
            if (basePhoneme.isNotBlank()) {
                val finalPhoneme = if (isPossessive) "${basePhoneme}z" else basePhoneme
                resultParts.add(finalPhoneme)
            }
            i++
        }

        if (resultParts.isEmpty()) return ""

        // Đảm bảo punc_norm giống sea_g2p: cuối câu luôn có dấu chấm kết thúc nếu chưa có dấu câu
        val lastIdx = resultParts.lastIndex
        val lastPart = resultParts[lastIdx]
        if (lastPart.isNotEmpty() && lastPart.last() !in PUNCTUATION_SET && !lastPart.endsWith("|>")) {
            resultParts[lastIdx] = "$lastPart."
        }

        return resultParts.joinToString(" ")
    }

    /**
     * Tra cứu hoặc suy luận IPA cho 1 từ đơn (hỗ trợ cả Tiếng Việt và Tên riêng Tiếng Anh / nước ngoài).
     */
    internal fun resolveSingleWordIpa(lowerWord: String, dict: Map<String, String>): String {
        if (lowerWord.isEmpty()) return ""

        // 1. Tra trực tiếp trong từ điển 56.000 từ Việt - Anh
        dict[lowerWord]?.let { return it }
        EXTRA_NOVEL_NAMES_IPA[lowerWord]?.let { return it }

        // 2. Nếu có chứa chữ cái có dấu Tiếng Việt hoặc khớp cấu trúc âm tiết đơn Tiếng Việt -> dùng bộ quy tắc âm tiết Việt
        if (isLikelyVietnameseSyllable(lowerWord)) {
            return fallbackVietnameseSyllableToIpa(lowerWord, dict)
        }

        // 3. Nếu là tên nhân vật / từ Tiếng Anh hoặc La-tinh chưa có trong từ điển:
        //    Thử bỏ hậu tố số nhiều (-s, -es, -ed, -ing) để tra từ gốc trước, nếu vẫn chưa có thì chạy bộ quy tắc G2P Tiếng Anh
        if (lowerWord.length > 4 && lowerWord.endsWith("s") && !lowerWord.endsWith("ss")) {
            val stem = lowerWord.dropLast(1)
            dict[stem]?.let { return "${it}z" }
            EXTRA_NOVEL_NAMES_IPA[stem]?.let { return "${it}z" }
        }

        return englishWordToIpa(lowerWord)
    }

    /**
     * Kiểm tra một từ có phải là âm tiết Tiếng Việt hợp lệ hay không.
     * Các tên tiếng Anh như "arthur", "harry", "sherlock", "michael", "john", "smith" sẽ trả về false.
     */
    internal fun isLikelyVietnameseSyllable(lowerWord: String): Boolean {
        if (lowerWord.any { it in VIETNAMESE_DIACRITIC_CHARS }) {
            return true
        }
        // Các chữ cái f, j, w, z không tồn tại trong âm tiết thuần Việt chuẩn
        if (lowerWord.any { it == 'f' || it == 'j' || it == 'w' || it == 'z' }) {
            return false
        }
        return VIETNAMESE_SYLLABLE_REGEX.matches(lowerWord)
    }

    /**
     * Bộ quy tắc G2P Tiếng Anh / Tên nhân vật nước ngoài (English & Foreign Proper Name G2P):
     * Chuyển đổi bất kỳ tên riêng Tiếng Anh / Romaji / La-tinh nhiều âm tiết nào sang chuỗi âm vị IPA
     * tương thích 100% với bảng `vocab` trong `config.json` của VieNeu-TTS v3 Nano.
     */
    internal fun englishWordToIpa(word: String): String {
        val clean = word.lowercase().filter { it in 'a'..'z' }
        if (clean.isEmpty()) return ""

        // Rút gọn phụ âm kép (ví dụ: "harry" -> "hary", "potter" -> "poter", "bellatrix" -> "belatrix")
        val dedup = StringBuilder(clean.length)
        var idx = 0
        while (idx < clean.length) {
            val ch = clean[idx]
            if (idx + 1 < clean.length && clean[idx + 1] == ch && ch !in "aeiou") {
                dedup.append(ch)
                idx += 2
            } else {
                dedup.append(ch)
                idx++
            }
        }

        val s = dedup.toString()
        val n = s.length
        val ipa = StringBuilder(n * 2)
        var hasStress = false
        var pos = 0

        fun isVowelChar(c: Char): Boolean = c in "aeiouy"

        while (pos < n) {
            val rem = n - pos
            val c = s[pos]
            val next = if (pos + 1 < n) s[pos + 1] else '\u0000'
            val next2 = if (pos + 2 < n) s[pos + 2] else '\u0000'

            // Chèn dấu trọng âm chính ˈ trước cụm nguyên âm đầu tiên của tên nhân vật
            if (!hasStress && isVowelChar(c) && !(pos == n - 1 && c == 'e' && n > 3)) {
                ipa.append('ˈ')
                hasStress = true
            }

            // 1. Cụm 4 ký tự cuối từ phổ biến (-tion, -sion)
            if (rem >= 4 && s.startsWith("tion", pos)) {
                ipa.append("ʃən")
                pos += 4
                continue
            }
            if (rem >= 4 && s.startsWith("sion", pos)) {
                ipa.append("ʒən")
                pos += 4
                continue
            }

            // 2. Cụm 3 ký tự (igh, tch, dge)
            if (rem >= 3 && s.startsWith("igh", pos)) {
                if (!hasStress) { ipa.append('ˈ'); hasStress = true }
                ipa.append("aɪ")
                pos += 3
                continue
            }
            if (rem >= 3 && s.startsWith("tch", pos)) {
                ipa.append("tʃ")
                pos += 3
                continue
            }
            if (rem >= 3 && s.startsWith("dge", pos)) {
                ipa.append("dʒ")
                pos += 3
                continue
            }

            // 3. Cụm 2 ký tự phụ âm & nguyên âm ghép (Digraphs)
            if (rem >= 2) {
                val pair = s.substring(pos, pos + 2)
                val mappedPair = when (pair) {
                    "sh" -> "ʃ"
                    "ch" -> "tʃ"
                    "th" -> "θ"
                    "ph" -> "f"
                    "wh" -> "w"
                    "ck" -> "k"
                    "qu" -> "kw"
                    "kn" -> if (pos == 0) "n" else null
                    "wr" -> if (pos == 0) "ɹ" else null
                    "ng" -> "ŋ"
                    "nk" -> "ŋk"
                    "ee" -> "iː"
                    "ea" -> "iː"
                    "oo" -> "uː"
                    "ou" -> "aʊ"
                    "ow" -> if (pos + 2 == n) "oʊ" else "aʊ"
                    "oi", "oy" -> "ɔɪ"
                    "ai", "ay" -> "eɪ"
                    "ei", "ey" -> "eɪ"
                    "au", "aw" -> "ɔː"
                    "oa" -> "oʊ"
                    "ar" -> if (pos + 2 < n && isVowelChar(next2)) "æɹ" else "ɑːɹ"
                    "er", "ir", "ur" -> if (pos + 2 < n && isVowelChar(next2)) "ɛɹ" else "ɚ"
                    "or" -> "ɔːɹ"
                    "le" -> if (pos + 2 == n && pos > 0 && !isVowelChar(s[pos - 1])) "əl" else null
                    else -> null
                }
                if (mappedPair != null) {
                    ipa.append(mappedPair)
                    pos += 2
                    continue
                }
            }

            // 4. Âm 'e' câm ở cuối tên tiếng Anh (ví dụ: "kate", "luke", "drake", "steele")
            if (c == 'e' && pos == n - 1 && n > 3 && !isVowelChar(s[pos - 1])) {
                pos++
                continue
            }

            // 5. Nguyên âm đơn (kiểm tra Magic-E: nguyên âm + phụ âm + 'e' cuối từ)
            val isMagicE = (pos + 2 == n - 1 && !isVowelChar(next) && next2 == 'e')
            val singleIpa = when (c) {
                'a' -> if (isMagicE) "eɪ" else if (pos == n - 1) "ə" else "æ"
                'e' -> if (isMagicE) "iː" else "ɛ"
                'i' -> if (isMagicE) "aɪ" else if (pos == n - 1) "i" else "ɪ"
                'o' -> if (isMagicE || pos == n - 1) "oʊ" else "ɑː"
                'u' -> if (isMagicE) "juː" else "ʌ"
                'y' -> if (pos == 0) "j" else if (pos == n - 1) "i" else "ɪ"
                'b' -> "b"
                'c' -> if (next == 'e' || next == 'i' || next == 'y') "s" else "k"
                'd' -> "d"
                'f' -> "f"
                'g' -> if (next == 'e' || next == 'i' || next == 'y') "dʒ" else "ɡ"
                'h' -> "h"
                'j' -> "dʒ"
                'k' -> "k"
                'l' -> "l"
                'm' -> "m"
                'n' -> "n"
                'p' -> "p"
                'q' -> "k"
                'r' -> "ɹ"
                's' -> if (pos > 0 && pos + 1 < n && isVowelChar(s[pos - 1]) && isVowelChar(next)) "z" else "s"
                't' -> "t"
                'v' -> "v"
                'w' -> "w"
                'x' -> if (pos == 0) "z" else "ks"
                'z' -> "z"
                else -> ""
            }
            ipa.append(singleIpa)
            pos++
        }

        val out = ipa.toString()
        return if (!hasStress && out.isNotEmpty()) "ˈ$out" else out
    }

    private fun tokenizeWordsAndPunct(text: String): List<String> {
        val tokens = ArrayList<String>()
        val regex = Regex("""<\|emotion_\d+\|>|[\p{L}\p{M}_]+|[.,!?;:]""")
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
     * Suy luận âm vị dự phòng cho các từ thuần Việt hiếm không có trong từ điển.
     */
    private fun fallbackVietnameseSyllableToIpa(word: String, dict: Map<String, String>): String {
        if (word.isEmpty()) return ""

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

        dict[baseWord]?.let { basePh ->
            return if (tone.isEmpty()) basePh else basePh + tone
        }

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
        return "${onsetIpa}ˈ${nucleusIpa}${tone}${codaIpa}"
    }
}
