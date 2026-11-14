package com.novelr.reader.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class LoadedTxtDocument(
    val fileName: String,
    val content: String
)

object TxtFileLoader {

    // Giới hạn kích thước file .txt tối đa 15 MB để chống tràn bộ nhớ (OutOfMemoryError / DoS)
    private const val MAX_TXT_FILE_BYTES = 15 * 1024 * 1024
    private const val MAX_FILENAME_LENGTH = 120

    /**
     * Đọc nội dung file .txt từ Uri (Storage Access Framework) có giới hạn dung lượng an toàn
     * và tự động nhận diện bảng mã tiếng Việt (UTF-8, UTF-8 BOM, UTF-16LE, UTF-16BE, Windows-1258).
     */
    fun loadFromUri(context: Context, uri: Uri): Result<LoadedTxtDocument> {
        return try {
            val fileName = resolveFileName(context, uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                readBytesBounded(input, MAX_TXT_FILE_BYTES)
            } ?: return Result.failure(IllegalArgumentException("Không thể mở file đã chọn"))

            val decodedText = decodeVietnameseBytes(bytes)
            Result.success(
                LoadedTxtDocument(
                    fileName = fileName,
                    content = decodedText
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readBytesBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("File .txt vượt quá giới hạn an toàn (tối đa 15 MB)")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        val rawName = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Truyen.txt"

        val sanitized = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
            .take(MAX_FILENAME_LENGTH)

        return sanitized.ifBlank { "Truyen.txt" }
    }

    internal fun decodeVietnameseBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. Kiểm tra BOM (Byte Order Mark)
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // 2. Thử giải mã nghiêm ngặt theo UTF-8
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // Không phải UTF-8 chuẩn -> thử Windows-1258 (bảng mã tiếng Việt ANSI phổ biến trên Windows)
        }

        return try {
            val win1258 = Charset.forName("windows-1258")
            String(bytes, win1258)
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }
}
