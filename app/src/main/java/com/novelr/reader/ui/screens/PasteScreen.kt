package com.novelr.reader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.novelr.reader.util.TextSplitter

private val SAMPLE_VIETNAMESE_TEXT = """
    Buổi sáng hôm ấy, mặt trời vừa ló rạng qua ngọn đồi phía xa, chiếu những tia nắng vàng óng ả xuống thung lũng.
    Một cơn gió nhẹ thổi qua rặng thông già, làm xào xạc những cành lá biếc.
    Thành phố thức giấc trong tiếng chim ca ríu rít và làn sương sớm còn vương vấn trên từng phiến cỏ.
    Cậu thanh niên khẽ mỉm cười, gấp lại cuốn tiểu thuyết dang dở và bắt đầu một ngày mới đầy hy vọng.
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteScreen(
    rawText: String,
    onTextChanged: (String) -> Unit,
    onStartReading: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val wordCount = remember(rawText) { TextSplitter.countWords(rawText) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NovelR - Đọc Sách TTS") }
            )
        },
        floatingActionButton = {
            if (wordCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = onStartReading,
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    text = { Text("Bắt đầu đọc") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val clip = clipboardManager.getText()?.text
                        if (!clip.isNullOrBlank()) {
                            onTextChanged(clip)
                        }
                    }
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Dán từ Clipboard")
                }

                OutlinedButton(
                    onClick = { onTextChanged(SAMPLE_VIETNAMESE_TEXT) }
                ) {
                    Text("Đoạn mẫu tiếng Việt")
                }

                Spacer(modifier = Modifier.weight(1f))

                if (rawText.isNotEmpty()) {
                    IconButton(onClick = { onTextChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Xóa nội dung")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = rawText,
                    onValueChange = onTextChanged,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Text("Dán nội dung truyện, sách hoặc tài liệu tiếng Việt tại đây...")
                    },
                    shape = MaterialTheme.shapes.medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Độ dài: ${rawText.length} ký tự",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Khoảng $wordCount từ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
