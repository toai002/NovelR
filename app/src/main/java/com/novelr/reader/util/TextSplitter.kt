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
    private const val PLACEHOLDER_DOT = "\u0000"

    // Các từ viết tắt phổ biến trong tiếng Việt không nên ngắt câu tại dấu chấm
    private val ABBREVIATIONS_REGEX = Regex(
        "\\b(TP|TS|ThS|PGS|GS|BS|KS|CN|Ths|Mr|Mrs|Ms|Dr|Prof|St|No|Vol)\\.",
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

        val paragraphsResult = ArrayList<ReaderParagraph>()
        val flatSentences = ArrayList<String>()
        val rawParagraphs = normalized.split("\n")

        var globalSentenceIdx = 0
        var paragraphIdx = 0

        for (rawPara in rawParagraphs) {
            val trimmedPara = rawPara.trim()
            if (trimmedPara.isEmpty()) continue

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
     * Tách văn bản truyện dài thành danh sách các câu chuẩn xác:
     * 1. Bảo toàn các từ viết tắt tiếng Việt (TP. HCM, PGS. TS. Nguyễn Văn A...).
     * 2. Sử dụng BreakIterator theo chuẩn Locale("vi", "VN").
     * 3. Tự động chia nhỏ các câu quá dài (> 180 ký tự) theo dấu phẩy/chấm phẩy để tránh tràn bộ nhớ ONNX/TTS.
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

        if (result.isNotEmpty()) return result

        return trimmedPara.split(FALLBACK_PUNCTUATION_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Chia nhỏ câu quá dài (> 180 ký tự) tại các ranh giới mệnh đề (, ; : —)
     * để tối ưu thời gian suy luận ONNX và không vượt giới hạn của Android TTS.
     */
    private fun splitLongSentence(longSentence: String): List<String> {
        val clauses = longSentence.split(CLAUSE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (clauses.size <= 1) {
            return longSentence.chunked(MAX_SENTENCE_LENGTH).map { it.trim() }
        }

        val result = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (clause in clauses) {
            if (currentChunk.isEmpty()) {
                currentChunk.append(clause)
            } else if (currentChunk.length + 1 + clause.length <= MAX_SENTENCE_LENGTH) {
                currentChunk.append(" ").append(clause)
            } else {
                result.add(currentChunk.toString())
                currentChunk.clear()
                currentChunk.append(clause)
            }
        }
        if (currentChunk.isNotEmpty()) {
            result.add(currentChunk.toString())
        }
        return result
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
