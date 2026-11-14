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
import androidx.compose.material.icons.filled.GraphicEq
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
import com.novelr.reader.service.edgetts.EdgeTtsConstants
import com.novelr.reader.service.piper.PiperConstants
import com.novelr.reader.service.piper.PiperModelFamily
import com.novelr.reader.service.vieneu.VieNeuConstants
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: TtsSettings,
    readingTheme: ReadingTheme,
    availableEngines: List<EngineInfo>,
    availableSystemVoices: List<SystemVoiceInfo>,
    vieneuStatus: VieNeuModelStatus,
    downloadProgress: Float,
    piperStatus: VieNeuModelStatus,
    piperDownloadProgress: Float,
    onSpeechRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSentencePauseChange: (Int) -> Unit,
    onWarmVoiceFilterChange: (Boolean) -> Unit,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    onHeadsetAutoPlayPauseChange: (Boolean) -> Unit,
    onReadingThemeChange: (ReadingTheme) -> Unit,
    onResetVoiceDefaults: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onEngineSelected: (String) -> Unit,
    onSystemVoiceSelected: (String?) -> Unit,
    onEngineTypeSelected: (TtsEngineType) -> Unit,
    onVieNeuVoiceSelected: (String) -> Unit,
    onPiperVoiceSelected: (String) -> Unit,
    onEdgeVoiceSelected: (String) -> Unit,
    onLookaheadBufferSizeChange: (Int) -> Unit,
    onDownloadVieNeuModel: () -> Unit,
    onDownloadPiperModel: () -> Unit,
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

            // 2. CHỌN CÔNG NGHỆ GIỌNG ĐỌC (4 BỘ MÁY ĐỘC LẬP) & BỘ LỌC CHỐNG CHÓI DSP
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Công nghệ tổng hợp giọng nói (4 bộ máy)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = settings.engineType == TtsEngineType.EDGE_ONLINE,
                        onClick = { onEngineTypeSelected(TtsEngineType.EDGE_ONLINE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                        icon = {}
                    ) {
                        Text("Edge TTS", fontSize = 11.sp, maxLines = 1)
                    }

                    SegmentedButton(
                        selected = settings.engineType == TtsEngineType.PIPER_ON_DEVICE,
                        onClick = { onEngineTypeSelected(TtsEngineType.PIPER_ON_DEVICE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                        icon = {}
                    ) {
                        Text("Piper (73)", fontSize = 11.sp, maxLines = 1)
                    }

                    SegmentedButton(
                        selected = settings.engineType == TtsEngineType.VIENEU_ON_DEVICE,
                        onClick = { onEngineTypeSelected(TtsEngineType.VIENEU_ON_DEVICE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                        icon = {}
                    ) {
                        Text("VieNeu AI", fontSize = 11.sp, maxLines = 1)
                    }

                    SegmentedButton(
                        selected = settings.engineType == TtsEngineType.NATIVE_SYSTEM,
                        onClick = { onEngineTypeSelected(TtsEngineType.NATIVE_SYSTEM) },
                        shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                        icon = {}
                    ) {
                        Text("Hệ Thống", fontSize = 11.sp, maxLines = 1)
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
                                text = "Chống chói tai DSP (Dynamic De-Esser)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Giữ nguyên 100% cao độ (Pitch) tự nhiên; chỉ lọc bỏ gai âm xuýt & cộng hưởng chói ở dải 3.4kHz–7.5kHz bằng Biquad Notch + Multi-band EQ.",
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
            when (settings.engineType) {
                TtsEngineType.EDGE_ONLINE -> {
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
                                    text = "Microsoft Edge TTS (Trực tiếp • 24kHz)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                                            text = " Không cần tải model (Online)",
                                            color = Color(0xFF2E7D32),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "6 giọng Đa ngôn ngữ phản hồi siêu tốc (~150ms/câu nhờ tái sử dụng Warm WebSocket Pool) + đã khóa chuẩn hóa âm vị Tiếng Việt để các từ trong ngoặc kép (\"ha ha\", \"ồ\", \"hừ\", \"ok\"...) luôn đọc chuẩn Tiếng Việt 100%, giữ cố định 1 giọng duy nhất suốt truyện.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Chọn giọng Microsoft Edge (8 giọng)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Khuyên dùng: Ava / Andrew (150ms)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        val edgePairs = EdgeTtsConstants.PRESET_VOICES.chunked(2)
                        edgePairs.forEach { rowVoices ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowVoices.forEach { voice ->
                                    val isSelected = voice.id == settings.selectedEdgeVoice
                                    OutlinedCard(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onEdgeVoiceSelected(voice.id) },
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
                                                text = voice.description,
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
                }

                TtsEngineType.PIPER_ON_DEVICE -> {
                    val activePiperProfile = remember(settings.selectedPiperVoice) {
                        PiperConstants.findById(settings.selectedPiperVoice)
                    }

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
                                    text = "Piper VITS Rõ Chữ (73 Giọng Offline)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                when (piperStatus) {
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
                                                    text = " Sẵn sàng (~100ms/câu)",
                                                    color = Color(0xFF2E7D32),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    VieNeuModelStatus.DOWNLOADING -> {
                                        val downloadedMb = (piperDownloadProgress * 91).toInt()
                                        Text(
                                            text = "$downloadedMb/91 MB (${(piperDownloadProgress * 100).toInt()}%)",
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

                            if (piperStatus == VieNeuModelStatus.NOT_DOWNLOADED || piperStatus == VieNeuModelStatus.ERROR) {
                                Text(
                                    text = "Tải gói 2 mô hình Piper VITS (~91MB: VAIS-1000 HD + VIVOS 65 người đọc thật) để phát âm rõ từng chữ, tốc độ siêu nhanh ~100ms/câu.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Button(
                                    onClick = onDownloadPiperModel,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                    Text(
                                        if (piperStatus == VieNeuModelStatus.ERROR) " Tải Tiếp Gói 73 Giọng Piper (~91MB)"
                                        else " Tải Gói 73 Giọng Piper Rõ Chữ (~91MB)"
                                    )
                                }
                            } else if (piperStatus == VieNeuModelStatus.DOWNLOADING) {
                                LinearProgressIndicator(
                                    progress = { piperDownloadProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // NHÓM A: 8 GIỌNG HD PHÁT THANH VIÊN RÕ CHỮ (VAIS-1000)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Nhóm 1: 8 Giọng HD Phát Thanh Viên (VAIS-1000)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Rõ chữ • 22kHz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        val hdPairs = PiperConstants.HD_PRESET_VOICES.chunked(2)
                        hdPairs.forEach { rowVoices ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowVoices.forEach { voice ->
                                    val isSelected = voice.id == settings.selectedPiperVoice
                                    OutlinedCard(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onPiperVoiceSelected(voice.id) },
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
                                                text = voice.description,
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

                    // NHÓM B: 65 GIỌNG NGƯỜI ĐỌC THẬT ĐA DẠNG (VIVOS #01 – #65)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (activePiperProfile.family == PiperModelFamily.VIVOS_65) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val currentSpeakerNumber = if (activePiperProfile.family == PiperModelFamily.VIVOS_65) {
                                activePiperProfile.speakerId + 1
                            } else {
                                1
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Nhóm 2: 65 Giọng Thật VIVOS (#01 – #65)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (activePiperProfile.family == PiperModelFamily.VIVOS_65) {
                                        "Đang chọn: Giọng #${String.format(Locale.US, "%02d", currentSpeakerNumber)}"
                                    } else {
                                        "Kéo thanh để chọn 1 trong 65 giọng"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Text(
                                text = "65 người đọc thật trong kho ngữ liệu VIVOS Tiếng Việt. Mỗi số là một âm sắc giọng độc lập, phát âm rõ chữ, không trộn lẫn.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Slider(
                                value = currentSpeakerNumber.toFloat(),
                                onValueChange = { value ->
                                    val speakerIdx = (value.roundToInt() - 1).coerceIn(0, 64)
                                    onPiperVoiceSelected("vivos_$speakerIdx")
                                },
                                valueRange = 1f..65f,
                                steps = 63
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(0, 2, 5, 11, 18, 24, 32, 41, 50, 64).forEach { sid ->
                                    val vid = "vivos_$sid"
                                    val label = "#${String.format(Locale.US, "%02d", sid + 1)}"
                                    FilterChip(
                                        selected = settings.selectedPiperVoice == vid,
                                        onClick = { onPiperVoiceSelected(vid) },
                                        label = { Text("VIVOS $label", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }

                TtsEngineType.VIENEU_ON_DEVICE -> {
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
                                    text = "Tải gói 4 mô hình ONNX thật (~256MB) một lần duy nhất để đọc truyền cảm offline. Trong lúc tải, app vẫn đọc bình thường bằng giọng hệ thống.",
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

                    // Lưới 2 cột × 3 hàng cho 6 giọng đọc nhân vật VieNeu
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Giọng đọc nhân vật (6 giọng AI truyền cảm)",
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
                }

                TtsEngineType.NATIVE_SYSTEM -> {
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

            if (settings.engineType == TtsEngineType.VIENEU_ON_DEVICE ||
                settings.engineType == TtsEngineType.PIPER_ON_DEVICE ||
                settings.engineType == TtsEngineType.EDGE_ONLINE
            ) {
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
                            text = "${settings.lookaheadBufferSize} câu (Mặc định: 8)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = settings.lookaheadBufferSize.coerceIn(2, 12).toFloat(),
                        onValueChange = { onLookaheadBufferSizeChange(it.toInt().coerceIn(2, 12)) },
                        valueRange = 2f..12f,
                        steps = 9
                    )
                }
            }

            HorizontalDivider()

            // 7. CHẾ ĐỘ CHẠY NỀN KHI TẮT MÀN HÌNH & TỰ ĐỘNG TAI NGHE
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tự động phát/dừng khi kết nối tai nghe",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Bắt đầu đọc khi cắm/kết nối tai nghe (Dây hoặc Bluetooth); tự dừng lại ngay khi rút hoặc ngắt kết nối.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.headsetAutoPlayPause,
                            onCheckedChange = onHeadsetAutoPlayPauseChange
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
