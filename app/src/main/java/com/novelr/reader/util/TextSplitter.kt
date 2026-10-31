package com.novelr.reader.util

import java.text.BreakIterator
import java.util.Locale

object TextSplitter {

    private const val MAX_SENTENCE_LENGTH = 300
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
     * Tách văn bản truyện dài thành danh sách các câu chuẩn xác:
     * 1. Bảo toàn các từ viết tắt tiếng Việt (TP. HCM, PGS. TS. Nguyễn Văn A...).
     * 2. Sử dụng BreakIterator theo chuẩn Locale("vi", "VN").
     * 3. Tự động chia nhỏ các câu quá dài (> 300 ký tự) theo dấu phẩy/chấm phẩy để tránh tràn bộ nhớ ONNX/TTS.
     */
    fun splitIntoSentences(text: String, locale: Locale = Locale("vi", "VN")): List<String> {
        if (text.isBlank()) return emptyList()

        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(WHITESPACE_NORMALIZE_REGEX, " ")

        val sentences = mutableListOf<String>()
        val paragraphs = normalized.split("\n")

        for (paragraph in paragraphs) {
            val trimmedPara = paragraph.trim()
            if (trimmedPara.isEmpty()) continue

            // Bảo vệ dấu chấm trong từ viết tắt trước khi đưa vào BreakIterator
            val protectedPara = ABBREVIATIONS_REGEX.replace(trimmedPara) { matchResult ->
                "${matchResult.groupValues[1]}$PLACEHOLDER_DOT"
            }

            val iterator = BreakIterator.getSentenceInstance(locale)
            iterator.setText(protectedPara)

            var start = iterator.first()
            var end = iterator.next()

            while (end != BreakIterator.DONE) {
                val rawSentence = protectedPara
                    .substring(start, end)
                    .replace(PLACEHOLDER_DOT, ".")
                    .trim()

                if (rawSentence.isNotBlank()) {
                    if (rawSentence.length <= MAX_SENTENCE_LENGTH) {
                        sentences.add(rawSentence)
                    } else {
                        sentences.addAll(splitLongSentence(rawSentence))
                    }
                }
                start = end
                end = iterator.next()
            }
        }

        return if (sentences.isNotEmpty()) {
            sentences
        } else {
            normalized.split(FALLBACK_PUNCTUATION_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    /**
     * Chia nhỏ câu quá dài (> 300 ký tự) tại các ranh giới mệnh đề (, ; : —)
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
