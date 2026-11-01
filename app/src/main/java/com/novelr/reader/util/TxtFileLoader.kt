package com.novelr.reader.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class LoadedTxtDocument(
    val fileName: String,
    val content: String
)

object TxtFileLoader {

    /**
     * Đọc nội dung file .txt từ Uri (Storage Access Framework) và tự động nhận diện bảng mã tiếng Việt
     * (UTF-8, UTF-8 BOM, UTF-16LE, UTF-16BE, Windows-1258).
     */
    fun loadFromUri(context: Context, uri: Uri): Result<LoadedTxtDocument> {
        return try {
            val fileName = resolveFileName(context, uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
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

    private fun resolveFileName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Truyen.txt"
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
