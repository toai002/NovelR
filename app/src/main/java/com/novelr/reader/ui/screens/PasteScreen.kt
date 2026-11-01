package com.novelr.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelr.reader.util.TextSplitter

private const val MAX_EDITOR_PREVIEW_CHARS = 35_000

private val SAMPLE_VIETNAMESE_TEXT = """
    Buổi sáng hôm ấy, mặt trời vừa ló rạng qua ngọn đồi phía xa, chiếu những tia nắng vàng óng ả xuống thung lũng.
    Một cơn gió nhẹ thổi qua rặng thông già, làm xào xạc những cành lá biếc.
    Thành phố thức giấc trong tiếng chim ca ríu rít và làn sương sớm còn vương vấn trên từng phiến cỏ.
    Cậu thanh niên khẽ mỉm cười, gấp lại cuốn tiểu thuyết dang dở và bắt đầu một ngày mới đầy hy vọng.
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PasteScreen(
    rawText: String,
    bookTitle: String,
    onTextChanged: (String) -> Unit,
    onPickTxtFile: (Uri) -> Unit,
    onStartReading: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val wordCount = remember(rawText) { TextSplitter.countWords(rawText) }
    val isLargeFile = rawText.length > MAX_EDITOR_PREVIEW_CHARS
    val displayPreviewText = remember(rawText) {
        if (isLargeFile) {
            rawText.take(MAX_EDITOR_PREVIEW_CHARS) + "\n\n... [File dài ${rawText.length} ký tự — Toàn bộ nội dung đã được nạp sẵn sàng để đọc]"
        } else {
            rawText
        }
    }

    val txtFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onPickTxtFile(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NovelR - Đọc Sách & Truyện TTS") }
            )
        },
        floatingActionButton = {
            if (wordCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = onStartReading,
                    icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                    text = { Text("Bắt đầu đọc ($wordCount từ)") }
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        txtFilePickerLauncher.launch(arrayOf("text/plain", "text/*"))
                    }
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mở File .TXT")
                }

                FilledTonalButton(
                    onClick = {
                        val clip = clipboardManager.getText()?.text
                        if (!clip.isNullOrBlank()) {
                            onTextChanged(clip)
                        }
                    }
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dán Clipboard")
                }

                OutlinedButton(
                    onClick = { onTextChanged(SAMPLE_VIETNAMESE_TEXT) }
                ) {
                    Text("Đoạn mẫu")
                }

                if (rawText.isNotEmpty()) {
                    IconButton(onClick = { onTextChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Xóa nội dung")
                    }
                }
            }

            if (bookTitle.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = bookTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Đã nạp $wordCount từ (${rawText.length} ký tự)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = displayPreviewText,
                    onValueChange = { newValue ->
                        if (!isLargeFile) {
                            onTextChanged(newValue)
                        }
                    },
                    readOnly = isLargeFile,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Text("Nhấn 'Mở File .TXT' để đọc truyện từ điện thoại, hoặc dán văn bản tiếng Việt tại đây...")
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
