package com.novelr.reader

import com.novelr.reader.util.TextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSplitterTest {

    @Test
    fun `splitIntoSentences should correctly split Vietnamese paragraphs and sentences`() {
        val sampleText = """
            Trời hôm nay nhiều mây. Gió bắt đầu thổi mạnh!
            Liệu trời có mưa không? Có lẽ là vậy...
        """.trimIndent()

        val sentences = TextSplitter.splitIntoSentences(sampleText)

        assertEquals(4, sentences.size)
        assertEquals("Trời hôm nay nhiều mây.", sentences[0])
        assertEquals("Gió bắt đầu thổi mạnh!", sentences[1])
        assertEquals("Liệu trời có mưa không?", sentences[2])
        assertTrue(sentences[3].startsWith("Có lẽ là vậy"))
    }

    @Test
    fun `splitIntoSentences should preserve Vietnamese abbreviations without premature splitting`() {
        val text = "PGS. TS. Nguyễn Văn An đang công tác tại TP. Hồ Chí Minh. Ông rất yêu sách."
        val sentences = TextSplitter.splitIntoSentences(text)

        assertEquals(2, sentences.size)
        assertEquals("PGS. TS. Nguyễn Văn An đang công tác tại TP. Hồ Chí Minh.", sentences[0])
        assertEquals("Ông rất yêu sách.", sentences[1])
    }

    @Test
    fun `splitIntoSentences should sub-split overly long sentences exceeding 300 chars at clause boundaries`() {
        val clause = "Trên con đường dài ngập tràn lá vàng rơi trong buổi chiều thu tĩnh lặng của vùng ngoại ô xa xôi, "
        val longParagraph = clause.repeat(5) + "cậu bé vẫn miệt mài bước đi."

        val sentences = TextSplitter.splitIntoSentences(longParagraph)
        assertTrue("Long sentence should be split into multiple chunks", sentences.size >= 2)
        assertTrue("Each chunk should be <= 300 characters", sentences.all { it.length <= 300 })
    }

    @Test
    fun `splitIntoSentences should handle blank or empty text`() {
        val sentences = TextSplitter.splitIntoSentences("   \n\t\r\n  ")
        assertTrue(sentences.isEmpty())
    }

    @Test
    fun `countWords should accurately count words without extra allocations`() {
        assertEquals(0, TextSplitter.countWords(""))
        assertEquals(0, TextSplitter.countWords("   \n\t  "))
        assertEquals(4, TextSplitter.countWords("Xin   chào\nViệt\tNam!"))
    }
}
