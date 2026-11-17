package com.novelr.reader.util

import java.text.BreakIterator
import java.util.Locale

/**
 * Biểu diễn một câu nằm ngầm bên trong một đoạn văn bản hiển thị.
 * [startInParagraph] và [endInParagraph] ánh xạ chính xác vị trí ký tự trong đoạn văn
 * để khi người dùng chạm vào chữ bất kỳ trên màn hình sẽ biết chính xác cần đọc câu nào.
 */
data class SentenceSpan(
    val sentenceIndex: Int,
    val text: String,
    val startInParagraph: Int,
    val endInParagraph: Int
)

/**
 * Biểu diễn một đoạn văn hoàn chỉnh giữ nguyên cấu trúc hiển thị của tác giả,
 * bên dưới chứa danh sách các [spans] câu đã được tách ngầm.
 */
data class ReaderParagraph(
    val paragraphIndex: Int,
    val fullText: String,
    val spans: List<SentenceSpan>
) {
    fun containsSentence(globalSentenceIndex: Int): Boolean {
        if (spans.isEmpty()) return false
        return globalSentenceIndex in spans.first().sentenceIndex..spans.last().sentenceIndex
    }

    fun findSentenceIndexAtCharOffset(charOffset: Int): Int? {
        if (spans.isEmpty()) return null
        for (span in spans) {
            if (charOffset in span.startInParagraph..span.endInParagraph) {
                return span.sentenceIndex
            }
        }
        // Nếu chạm vào khoảng trắng giữa 2 câu, lấy câu gần nhất
        return spans.minByOrNull { span ->
            when {
                charOffset < span.startInParagraph -> span.startInParagraph - charOffset
                charOffset > span.endInParagraph -> charOffset - span.endInParagraph
                else -> 0
            }
        }?.sentenceIndex
    }
}

data class ParsedBookContent(
    val paragraphs: List<ReaderParagraph>,
    val sentences: List<String>
)

object TextSplitter {

    private const val MAX_SENTENCE_LENGTH = 180
    private const val MIN_STANDALONE_WORDS = 4
    private const val MIN_INTERJECTION_SENTENCE_WORDS = 7
    private const val PLACEHOLDER_DOT = "\u0000"

    // Các từ viết tắt phổ biến trong tiếng Việt không nên ngắt câu tại dấu chấm
    private val ABBREVIATIONS_REGEX = Regex(
        "\\b(TP|TS|ThS|PGS|GS|BS|KS|CN|Ths|Mr|Mrs|Ms|Dr|Prof|St|No|Vol)\\.",
        RegexOption.IGNORE_CASE
    )

    // Nhận diện câu có chứa từ cảm thán, tiếng cười hoặc lời thoại ngắn dễ bị đọc lỗi nếu tách quá ngắn
    private val INTERJECTION_DETECTOR_REGEX = Regex(
        "(?:^|[\\s\"'“”‘’«»「」『』—–-])(?:ha\\s*ha|haha|hà\\s*ha|hà\\s*hà|hi\\s*hi|hihi|hì\\s*hi|hì\\s*hì|" +
                "he\\s*he|hehe|hê\\s*hê|hề\\s*he|hề\\s*hề|hu\\s*hu|huhu|hù\\s*hu|hù\\s*hù|" +
                "hô\\s*hô|hoho|ho\\s*ho|hồ\\s*hô|khà\\s*khà|ka\\s*ka|kaka|hắc\\s*hắc|" +
                "ồ|ố|ổ|ỗ|ộ|oh|à|á|ả|ã|ạ|ah|ơ|ớ|ờ|ở|ỡ|ợ|ừ|ứ|ử|ữ|ự|ưm|ừm|hừ|hứ|hự|hừm|hmm|hm|uhm|um|" +
                "hả|hở|chà|ái|ối|ôi|ơi|ê|ế|ề|ok|okay|alo)(?=$|[\\s,.!?;:\"'“”‘’«»「」『』…])",
        RegexOption.IGNORE_CASE
    )

    private val WHITESPACE_NORMALIZE_REGEX = Regex("[\\t \\u00A0]+")
    private val CLAUSE_SPLIT_REGEX = Regex("(?<=[,;:])\\s+|\\s+(?=[—–-])")
    private val FALLBACK_PUNCTUATION_REGEX = Regex("(?<=[.!?…])\\s+")

    /**
     * Phân tích văn bản thành:
     * 1. Danh sách các đoạn văn [ReaderParagraph] giữ nguyên cấu trúc hiển thị tự nhiên trên màn hình đọc.
     * 2. Danh sách các câu phẳng [ParsedBookContent.sentences] cùng ánh xạ vị trí ký tự ngầm [SentenceSpan]
     *    để bộ máy TTS/VieNeu đọc từng câu và hỗ trợ chạm vào chữ bất kỳ để đọc câu đó.
     * 3. Tự động gộp các dòng cảm thán quá ngắn (như dòng chỉ có `"Ha ha!"`, `"Ồ!"`) vào đoạn kế tiếp
     *    để không bao giờ tạo ra câu đơn lẻ quá ít từ gây lỗi đọc từ cảm thán.
     */
    fun parseBookContent(
        text: String,
        locale: Locale = Locale("vi", "VN")
    ): ParsedBookContent {
        if (text.isBlank()) return ParsedBookContent(emptyList(), emptyList())

        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(WHITESPACE_NORMALIZE_REGEX, " ")

        val rawLines = normalized.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val mergedParagraphLines = mergeShortInterjectionParagraphs(rawLines)

        val paragraphsResult = ArrayList<ReaderParagraph>()
        val flatSentences = ArrayList<String>()

        var globalSentenceIdx = 0
        var paragraphIdx = 0

        for (trimmedPara in mergedParagraphLines) {
            val paraSentences = splitParagraphIntoSentences(trimmedPara, locale)
            if (paraSentences.isEmpty()) continue

            val paraBuilder = StringBuilder(trimmedPara.length + 8)
            val spans = ArrayList<SentenceSpan>(paraSentences.size)

            for ((i, sent) in paraSentences.withIndex()) {
                if (i > 0) {
                    paraBuilder.append(' ')
                }
                val startChar = paraBuilder.length
                paraBuilder.append(sent)
                val endChar = paraBuilder.length

                spans.add(
                    SentenceSpan(
                        sentenceIndex = globalSentenceIdx,
                        text = sent,
                        startInParagraph = startChar,
                        endInParagraph = endChar
                    )
                )
                flatSentences.add(sent)
                globalSentenceIdx++
            }

            paragraphsResult.add(
                ReaderParagraph(
                    paragraphIndex = paragraphIdx,
                    fullText = paraBuilder.toString(),
                    spans = spans
                )
            )
            paragraphIdx++
        }

        return ParsedBookContent(
            paragraphs = paragraphsResult,
            sentences = flatSentences
        )
    }

    /**
     * Nếu trong truyện có dòng riêng lẻ chỉ chứa từ cảm thán / câu quá ngắn (<= 3 từ, hoặc <= 6 từ có từ cảm thán
     * như `"Ha ha!"`, `"Ồ, thế à?"`), gộp dòng đó vào dòng kế tiếp (hoặc dòng trước) để câu luôn đủ ngữ cảnh Tiếng Việt.
     */
    private fun mergeShortInterjectionParagraphs(lines: List<String>): List<String> {
        if (lines.size <= 1) return lines
        val merged = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            var current = lines[i]
            while (i + 1 < lines.size && isTooShortOrInterjection(current)) {
                val next = lines[i + 1]
                if (current.length + 1 + next.length <= MAX_SENTENCE_LENGTH) {
                    current = "$current $next"
                    i++
                } else {
                    break
                }
            }
            if (isTooShortOrInterjection(current) && merged.isNotEmpty()) {
                val prevIdx = merged.lastIndex
                val prev = merged[prevIdx]
                if (prev.length + 1 + current.length <= MAX_SENTENCE_LENGTH) {
                    merged[prevIdx] = "$prev $current"
                    i++
                    continue
                }
            }
            merged.add(current)
            i++
        }
        return merged
    }

    /**
     * Tách văn bản truyện dài thành danh sách các câu chuẩn xác:
     * 1. Bảo toàn các từ viết tắt tiếng Việt (TP. HCM, PGS. TS. Nguyễn Văn A...).
     * 2. Sử dụng BreakIterator theo chuẩn Locale("vi", "VN").
     * 3. Tự động chia nhỏ các câu quá dài (> 180 ký tự) theo dấu phẩy/chấm phẩy nhưng đảm bảo mỗi vế luôn đủ từ.
     * 4. Gộp triệt để các câu quá ít từ hoặc câu cảm thán ngắn vào câu liền kề.
     */
    fun splitIntoSentences(text: String, locale: Locale = Locale("vi", "VN")): List<String> {
        return parseBookContent(text, locale).sentences
    }

    private fun splitParagraphIntoSentences(trimmedPara: String, locale: Locale): List<String> {
        val protectedPara = ABBREVIATIONS_REGEX.replace(trimmedPara) { matchResult ->
            "${matchResult.groupValues[1]}$PLACEHOLDER_DOT"
        }

        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(protectedPara)

        val result = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()

        while (end != BreakIterator.DONE) {
            val rawSentence = protectedPara
                .substring(start, end)
                .replace(PLACEHOLDER_DOT, ".")
                .trim()

            if (rawSentence.isNotBlank()) {
                if (rawSentence.length <= MAX_SENTENCE_LENGTH) {
                    result.add(rawSentence)
                } else {
                    result.addAll(splitLongSentence(rawSentence))
                }
            }
            start = end
            end = iterator.next()
        }

        val rawList = if (result.isNotEmpty()) {
            result
        } else {
            trimmedPara.split(FALLBACK_PUNCTUATION_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        return mergeShortInterjectionSentences(rawList)
    }

    /**
     * Kiểm tra một câu có quá ít từ hoặc là câu cảm thán ngắn cần phải gộp với câu bên cạnh hay không:
     * - Mọi câu có `< 4` từ (1, 2, 3 từ) hoặc `<= 11` ký tự đều được gộp.
     * - Mọi câu có `< 7` từ mà chứa từ cảm thán/tiếng cười (`"ha ha"`, `"ồ"`, `"hừ"`, `"à"`, `"ok"`...)
     *   hoặc nằm trong ngoặc kép hội thoại ngắn đều được gộp vào câu kế tiếp/trước đó.
     */
    internal fun isTooShortOrInterjection(sentence: String): Boolean {
        val words = countWords(sentence)
        if (words < MIN_STANDALONE_WORDS || sentence.length <= 11) {
            return true
        }
        if (words < MIN_INTERJECTION_SENTENCE_WORDS && INTERJECTION_DETECTOR_REGEX.containsMatchIn(sentence)) {
            return true
        }
        return false
    }

    /**
     * Gộp hai chiều (tiến & lùi) các câu có ít từ hoặc chứa từ cảm thán ngắn vào câu liền kề trong đoạn văn:
     * - Bước 1 (Gộp tiến): Nếu câu hiện tại quá ngắn/có từ cảm thán, nối liên tiếp với câu phía sau cho đến khi đủ từ.
     * - Bước 2 (Gộp lùi): Nếu câu cuối cùng của đoạn văn vẫn quá ngắn (ví dụ `"... Hắn bật cười. Ha ha!"`),
     *   nối ngược vào câu đứng ngay trước nó.
     */
    private fun mergeShortInterjectionSentences(sentences: List<String>): List<String> {
        if (sentences.size <= 1) return sentences
        val forwardMerged = ArrayList<String>(sentences.size)
        var i = 0
        while (i < sentences.size) {
            var current = sentences[i]
            while (i + 1 < sentences.size && isTooShortOrInterjection(current)) {
                val next = sentences[i + 1]
                if (current.length + 1 + next.length <= MAX_SENTENCE_LENGTH) {
                    current = "$current $next"
                    i++
                } else {
                    break
                }
            }
            forwardMerged.add(current)
            i++
        }

        if (forwardMerged.size <= 1) return forwardMerged

        // Bước 2: Gộp lùi nếu câu ở cuối hoặc giữa vẫn còn quá ngắn
        val finalMerged = ArrayList<String>(forwardMerged.size)
        for (sent in forwardMerged) {
            if (isTooShortOrInterjection(sent) && finalMerged.isNotEmpty()) {
                val lastIdx = finalMerged.lastIndex
                val prev = finalMerged[lastIdx]
                if (prev.length + 1 + sent.length <= MAX_SENTENCE_LENGTH + 40) {
                    finalMerged[lastIdx] = "$prev $sent"
                    continue
                }
            }
            finalMerged.add(sent)
        }
        return finalMerged
    }

    /**
     * Chia nhỏ câu quá dài (> 180 ký tự) tại các ranh giới mệnh đề (, ; : —)
     * nhưng KHÔNG BAO GIỜ tách ra mệnh đề ngắn dưới 6 từ hoặc mệnh đề cảm thán cô lập.
     */
    private fun splitLongSentence(longSentence: String): List<String> {
        val clauses = longSentence.split(CLAUSE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (clauses.size <= 1) {
            return longSentence.chunked(MAX_SENTENCE_LENGTH).map { it.trim() }
        }

        val rawChunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (clause in clauses) {
            if (currentChunk.isEmpty()) {
                currentChunk.append(clause)
            } else {
                val currentWords = countWords(currentChunk.toString())
                val canFit = currentChunk.length + 1 + clause.length <= MAX_SENTENCE_LENGTH
                // Nếu vế hiện tại còn quá ít từ (< 6 từ), bắt buộc gộp tiếp mệnh đề sau chứ không ngắt sớm
                if (canFit || currentWords < 6) {
                    currentChunk.append(" ").append(clause)
                } else {
                    rawChunks.add(currentChunk.toString())
                    currentChunk.clear()
                    currentChunk.append(clause)
                }
            }
        }
        if (currentChunk.isNotEmpty()) {
            rawChunks.add(currentChunk.toString())
        }

        return mergeShortInterjectionSentences(rawChunks)
    }

    /**
     * Đếm số từ với độ phức tạp O(N) và 0 cấp phát bộ nhớ phụ (Zero-allocation),
     * tránh dùng Regex.split() gây giật lag UI khi dán chương truyện dài.
     */
    fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (i in 0 until text.length) {
            if (text[i].isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }
}
