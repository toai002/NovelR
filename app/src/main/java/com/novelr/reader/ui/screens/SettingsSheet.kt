package com.novelr.reader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelr.reader.model.EngineInfo
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.vieneu.VieNeuConstants
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: TtsSettings,
    availableEngines: List<EngineInfo>,
    vieneuStatus: VieNeuModelStatus,
    downloadProgress: Float,
    onSpeechRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSentencePauseChange: (Int) -> Unit,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onEngineSelected: (String) -> Unit,
    onEngineTypeSelected: (TtsEngineType) -> Unit,
    onVieNeuVoiceSelected: (String) -> Unit,
    onLookaheadBufferSizeChange: (Int) -> Unit,
    onDownloadVieNeuModel: () -> Unit,
    onDismissRequest: () -> Unit
) {
    var engineMenuExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "Cài Đặt Giọng Đọc & Chạy Nền",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // 1. CHẾ ĐỘ CHẠY NỀN KHI TẮT MÀN HÌNH
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Đọc chạy nền khi tắt màn hình",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Giữ CPU WakeLock & điều khiển qua màn hình khóa khi khóa máy hoặc thoát ra ngoài.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.backgroundPlayback,
                            onCheckedChange = onBackgroundPlaybackChange
                        )
                    }

                    if (settings.backgroundPlayback) {
                        FilledTonalButton(
                            onClick = onRequestIgnoreBatteryOptimizations,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Mở giới hạn Pin chạy nền (Xiaomi HyperOS)")
                        }
                    }
                }
            }

            // 2. CHỌN LOẠI CÔNG CỤ (SYSTEM TTS VS VIENEU AI ON-DEVICE)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Công nghệ giọng đọc",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = settings.engineType == TtsEngineType.VIENEU_ON_DEVICE,
                        onClick = { onEngineTypeSelected(TtsEngineType.VIENEU_ON_DEVICE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null) }
                    ) {
                        Text("VieNeu AI (On-Device)")
                    }

                    SegmentedButton(
                        selected = settings.engineType == TtsEngineType.NATIVE_SYSTEM,
                        onClick = { onEngineTypeSelected(TtsEngineType.NATIVE_SYSTEM) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) }
                    ) {
                        Text("Hệ Thống")
                    }
                }
            }

            // 3. NỘI DUNG CẤU HÌNH RIÊNG THEO ENGINE
            if (settings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mô hình: VieNeu-TTS v3 Nano (ONNX)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            when (vieneuStatus) {
                                VieNeuModelStatus.READY -> {
                                    Surface(
                                        color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = " Đã tải (24kHz)",
                                                color = Color(0xFF2E7D32),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                VieNeuModelStatus.DOWNLOADING -> {
                                    val downloadedMb = (downloadProgress * 256).toInt()
                                    Text(
                                        text = "$downloadedMb/256 MB (${(downloadProgress * 100).toInt()}%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                VieNeuModelStatus.ERROR -> {
                                    Text(
                                        text = "Cần tải lại",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                else -> {}
                            }
                        }

                        if (vieneuStatus == VieNeuModelStatus.NOT_DOWNLOADED || vieneuStatus == VieNeuModelStatus.ERROR) {
                            Text(
                                text = if (vieneuStatus == VieNeuModelStatus.ERROR) {
                                    "Kết nối gián đoạn khi tải model từ HuggingFace. Nhấn bên dưới để tiếp tục tải (~256MB)."
                                } else {
                                    "Tải bộ 4 mô hình ONNX thật của VieNeu-TTS v3 Nano (~256MB) một lần duy nhất để đọc offline. Trong lúc tải, app vẫn đọc bình thường bằng giọng hệ thống."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Button(
                                onClick = onDownloadVieNeuModel,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Text(
                                    if (vieneuStatus == VieNeuModelStatus.ERROR) " Tải Tiếp Gói Giọng Đọc AI (~256MB)"
                                    else " Tải Gói Giọng Đọc AI (~256MB)"
                                )
                            }
                        } else if (vieneuStatus == VieNeuModelStatus.DOWNLOADING) {
                            Text(
                                text = "Đang tải bộ mô hình VieNeu-TTS v3 Nano từ HuggingFace... Bạn có thể đóng bảng này và nghe đọc bình thường.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Chọn nhân vật giọng đọc VieNeu
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Chọn giọng đọc nhân vật",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    VieNeuConstants.PRESET_VOICES.forEach { voice ->
                        val isSelected = voice.id == settings.selectedVieNeuVoice
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onVieNeuVoiceSelected(voice.id) },
                            border = if (isSelected) BorderStroke(
                                2.dp,
                                MaterialTheme.colorScheme.primary
                            ) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = voice.name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = " (${voice.gender})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = voice.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // Cấu hình Pre-Buffering Lookahead Queue
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Đệm trước vào RAM (Lookahead)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "${settings.lookaheadBufferSize} câu tiếp theo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Render ngầm trước các câu sau để chuyển câu mượt mà không phải chờ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Slider(
                        value = settings.lookaheadBufferSize.toFloat(),
                        onValueChange = { onLookaheadBufferSizeChange(it.toInt()) },
                        valueRange = 1f..4f,
                        steps = 2
                    )
                }

            } else {
                // Lựa chọn System TTS Engine Picker
                Column {
                    Text(
                        text = "Hệ thống TTS được cài",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val selectedEngineLabel = availableEngines
                        .find { it.packageName == settings.enginePackage }
                        ?.label ?: "Mặc định hệ thống (Google / Samsung)"

                    OutlinedCard(
                        onClick = { engineMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = selectedEngineLabel, style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }

                        DropdownMenu(
                            expanded = engineMenuExpanded,
                            onDismissRequest = { engineMenuExpanded = false }
                        ) {
                            availableEngines.forEach { engine ->
                                DropdownMenuItem(
                                    text = { Text(engine.label) },
                                    onClick = {
                                        onEngineSelected(engine.packageName)
                                        engineMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 4. KHOẢNG NGHỈ CHUYỂN CÂU TỰ NHIÊN (SENTENCE PAUSE)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Khoảng nghỉ chuyển câu", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = String.format(Locale.US, "%.2f giây (%d ms)", settings.sentencePauseMs / 1000f, settings.sentencePauseMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Nhịp nghỉ lấy hơi tự nhiên giữa các câu để giọng đọc không bị gấp gáp.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Slider(
                    value = settings.sentencePauseMs.toFloat(),
                    onValueChange = { onSentencePauseChange((it / 50f).toInt() * 50) },
                    valueRange = 200f..1200f,
                    steps = 19
                )
            }

            // 5. TỐC ĐỘ ĐỌC (SPEECH RATE)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Tốc độ đọc", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = String.format(Locale.US, "%.2fx", settings.speechRate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = settings.speechRate,
                    onValueChange = onSpeechRateChange,
                    valueRange = 0.5f..2.0f,
                    steps = 15
                )
            }

            // 6. CAO ĐỘ GIỌNG (PITCH)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Cao độ giọng (Pitch)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = String.format(Locale.US, "%.2fx", settings.pitch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = settings.pitch,
                    onValueChange = onPitchChange,
                    valueRange = 0.5f..1.5f,
                    steps = 10
                )
            }
        }
    }
}
