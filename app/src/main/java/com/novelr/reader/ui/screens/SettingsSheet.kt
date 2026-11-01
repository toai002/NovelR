package com.novelr.reader.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelr.reader.model.EngineInfo
import com.novelr.reader.model.ReadingTheme
import com.novelr.reader.model.SystemVoiceInfo
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.vieneu.VieNeuConstants
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: TtsSettings,
    readingTheme: ReadingTheme,
    availableEngines: List<EngineInfo>,
    availableSystemVoices: List<SystemVoiceInfo>,
    vieneuStatus: VieNeuModelStatus,
    downloadProgress: Float,
    onSpeechRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSentencePauseChange: (Int) -> Unit,
    onWarmVoiceFilterChange: (Boolean) -> Unit,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    onReadingThemeChange: (ReadingTheme) -> Unit,
    onResetVoiceDefaults: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onEngineSelected: (String) -> Unit,
    onSystemVoiceSelected: (String?) -> Unit,
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // HEADER + NÚT TRỞ VỀ MẶC ĐỊNH
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cài Đặt Giọng Đọc",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (settings.isDefaultVoiceConfig) "Đang ở thông số giọng chuẩn mặc định"
                        else "Đã tùy chỉnh thông số giọng đọc",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                AssistChip(
                    onClick = onResetVoiceDefaults,
                    enabled = !settings.isDefaultVoiceConfig,
                    label = { Text("Về mặc định") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = "Khôi phục mặc định",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            // 1. GIAO DIỆN NỀN ĐỌC SÁCH DỊU MẮT
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Màu nền trang sách",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReadingTheme.entries.forEachIndexed { idx, theme ->
                        SegmentedButton(
                            selected = readingTheme == theme,
                            onClick = { onReadingThemeChange(theme) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = ReadingTheme.entries.size
                            )
                        ) {
                            Text(theme.label)
                        }
                    }
                }
            }

            // 2. CHỌN CÔNG NGHỆ GIỌNG ĐỌC & BỘ LỌC LÀM ẤM CHỐNG CHÓI TAI
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Công nghệ tổng hợp giọng nói",
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

                // BỘ LỌC LÀM ẤM GIỌNG ĐỌC (CHỐNG CHÓI TAI)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.warmVoiceFilter) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Làm ấm giọng đọc (Chống chói tai)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Khử âm xuýt gắt (De-Esser 4kHz–8kHz), hạ nhẹ độ căng giọng hệ thống & ưu tiên giọng trầm ấm.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.warmVoiceFilter,
                            onCheckedChange = onWarmVoiceFilterChange
                        )
                    }
                }
            }

            // 3. CẤU HÌNH GIỌNG THEO ENGINE
            if (settings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "VieNeu-TTS v3 Nano (ONNX 24kHz)",
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
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = " Sẵn sàng",
                                                color = Color(0xFF2E7D32),
                                                fontSize = 11.sp,
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
                                text = "Tải gói 4 mô hình ONNX thật (~256MB) một lần duy nhất để đọc offline. Trong lúc tải, app vẫn đọc bình thường bằng giọng hệ thống.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Button(
                                onClick = onDownloadVieNeuModel,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Text(
                                    if (vieneuStatus == VieNeuModelStatus.ERROR) " Tải Tiếp Gói Giọng AI (~256MB)"
                                    else " Tải Gói Giọng Đọc AI (~256MB)"
                                )
                            }
                        } else if (vieneuStatus == VieNeuModelStatus.DOWNLOADING) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Lưới 2 cột × 3 hàng cho 6 giọng đọc nhân vật VieNeu (gọn gàng, nhìn thấy toàn bộ ngay lập tức)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Giọng đọc nhân vật (6 giọng AI)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Mặc định: Đức Trí",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    val voicePairs = VieNeuConstants.PRESET_VOICES.chunked(2)
                    voicePairs.forEach { rowVoices ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowVoices.forEach { voice ->
                                val isSelected = voice.id == settings.selectedVieNeuVoice
                                OutlinedCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onVieNeuVoiceSelected(voice.id) },
                                    border = if (isSelected) BorderStroke(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary
                                    ) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = voice.name,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = voice.gender,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = voice.description.substringBefore(" (VieNeu"),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Chọn biến thể Giọng Tiếng Việt Hệ thống (Nam trầm / Nữ dịu) + Engine Picker
                if (availableSystemVoices.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Chọn âm sắc giọng Tiếng Việt Hệ thống",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = settings.selectedSystemVoice == null,
                                onClick = { onSystemVoiceSelected(null) },
                                label = { Text("Tự động (Ưu tiên giọng ấm)", fontSize = 12.sp) }
                            )
                            availableSystemVoices.take(8).forEach { voiceInfo ->
                                FilterChip(
                                    selected = settings.selectedSystemVoice == voiceInfo.name,
                                    onClick = { onSystemVoiceSelected(voiceInfo.name) },
                                    label = { Text(voiceInfo.label, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                // Lựa chọn System TTS Engine Picker
                Column {
                    Text(
                        text = "Bộ máy TTS Hệ thống",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val selectedEngineLabel = availableEngines
                        .find { it.packageName == settings.enginePackage }
                        ?.label ?: "Mặc định hệ thống (Google / Xiaomi)"

                    OutlinedCard(
                        onClick = { engineMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
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

            HorizontalDivider()

            // 4. TỐC ĐỘ ĐỌC (KÈM CHIP CHỌN NHANH & DEFAULT 1.0x)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tốc độ đọc",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
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
                    steps = 14
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        0.8f to "0.8x Chậm",
                        1.0f to "1.0x Mặc định",
                        1.2f to "1.2x Nhanh",
                        1.5f to "1.5x Rất nhanh"
                    ).forEach { (rate, label) ->
                        FilterChip(
                            selected = abs(settings.speechRate - rate) < 0.03f,
                            onClick = { onSpeechRateChange(rate) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }

            // 5. KHOẢNG NGHỈ CHUYỂN CÂU TỰ NHIÊN (KÈM CHIP MẶC ĐỊNH 550ms)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Khoảng nghỉ chuyển câu",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = String.format(Locale.US, "%.2f giây (%d ms)", settings.sentencePauseMs / 1000f, settings.sentencePauseMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = settings.sentencePauseMs.toFloat(),
                    onValueChange = { onSentencePauseChange((it / 50f).toInt() * 50) },
                    valueRange = 200f..1200f,
                    steps = 19
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        350 to "350ms Nhanh",
                        550 to "550ms Mặc định",
                        750 to "750ms Thong thả",
                        1000 to "1.0s Sâu lắng"
                    ).forEach { (ms, label) ->
                        FilterChip(
                            selected = settings.sentencePauseMs == ms,
                            onClick = { onSentencePauseChange(ms) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }

            // 6. CAO ĐỘ GIỌNG (PITCH) & BỘ ĐỆM RAM
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cao độ giọng (Pitch)",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = String.format(Locale.US, "%.2fx", settings.pitch),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        if (abs(settings.pitch - TtsSettings.DEFAULT_PITCH) > 0.02f) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "[Đặt lại 1.0x]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onPitchChange(TtsSettings.DEFAULT_PITCH) }
                            )
                        }
                    }
                }
                Slider(
                    value = settings.pitch,
                    onValueChange = onPitchChange,
                    valueRange = 0.5f..1.5f,
                    steps = 9
                )
            }

            if (settings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Bộ đệm trước vào RAM (Lookahead)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${settings.lookaheadBufferSize} câu (Mặc định: 2)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = settings.lookaheadBufferSize.toFloat(),
                        onValueChange = { onLookaheadBufferSizeChange(it.toInt()) },
                        valueRange = 1f..4f,
                        steps = 2
                    )
                }
            }

            HorizontalDivider()

            // 7. CHẾ ĐỘ CHẠY NỀN KHI TẮT MÀN HÌNH
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
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
                                text = "Giữ CPU WakeLock & điều khiển qua màn hình khóa khi khóa máy.",
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

            // NÚT KHÔI PHỤC MẶC ĐỊNH Ở CUỐI BẢNG
            OutlinedCard(
                onClick = onResetVoiceDefaults,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Khôi phục toàn bộ chỉnh giọng về Mặc định (Default)",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
