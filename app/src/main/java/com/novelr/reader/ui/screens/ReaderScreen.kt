package com.novelr.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.ReaderUiState
import com.novelr.reader.model.ReadingTheme
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.vieneu.VieNeuConstants
import java.util.Locale
import kotlin.math.roundToInt

private data class ReaderPalette(
    val pageBg: Color,
    val cardBg: Color,
    val activeCardBg: Color,
    val activeBorder: Color,
    val textPrimary: Color,
    val activeTextPrimary: Color,
    val textSecondary: Color
)

@Composable
private fun rememberReaderPalette(theme: ReadingTheme): ReaderPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(theme, scheme) {
        when (theme) {
            ReadingTheme.SEPIA -> ReaderPalette(
                pageBg = Color(0xFFF6EFE2),
                cardBg = Color(0xFFFAF5EB),
                activeCardBg = Color(0xFFEBDCC3),
                activeBorder = Color(0xFF9C5A25),
                textPrimary = Color(0xFF2B2118),
                activeTextPrimary = Color(0xFF1F160E),
                textSecondary = Color(0xFF756352)
            )
            ReadingTheme.DARK -> ReaderPalette(
                pageBg = Color(0xFF111317),
                cardBg = Color(0xFF1A1D24),
                activeCardBg = Color(0xFF233247),
                activeBorder = Color(0xFF60A5FA),
                textPrimary = Color(0xFFD6DAE1),
                activeTextPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0xFF8B94A3)
            )
            ReadingTheme.LIGHT -> ReaderPalette(
                pageBg = Color(0xFFF4F6F9),
                cardBg = Color(0xFFFFFFFF),
                activeCardBg = scheme.primaryContainer,
                activeBorder = scheme.primary,
                textPrimary = scheme.onSurface,
                activeTextPrimary = scheme.onPrimaryContainer,
                textSecondary = scheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    onBack: () -> Unit,
    onPickTxtFile: (Uri) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextSentence: () -> Unit,
    onPreviousSentence: () -> Unit,
    onSeekToSentence: (Int) -> Unit,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    onReadingThemeChange: (ReadingTheme) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSentencePauseChange: (Int) -> Unit,
    onWarmVoiceFilterChange: (Boolean) -> Unit,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    onResetVoiceDefaults: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onEngineSelected: (String) -> Unit,
    onSystemVoiceSelected: (String?) -> Unit,
    onEngineTypeSelected: (TtsEngineType) -> Unit,
    onVieNeuVoiceSelected: (String) -> Unit,
    onLookaheadBufferSizeChange: (Int) -> Unit,
    onDownloadVieNeuModel: () -> Unit
) {
    val listState = rememberLazyListState()
    var showSettingsSheet by remember { mutableStateOf(false) }
    val palette = rememberReaderPalette(uiState.readingTheme)

    val activeVoiceName = remember(uiState.settings.selectedVieNeuVoice) {
        VieNeuConstants.PRESET_VOICES.find { it.id == uiState.settings.selectedVieNeuVoice }?.name ?: "Đức Trí"
    }

    val txtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onPickTxtFile(uri)
        }
    }

    LaunchedEffect(uiState.currentIndex) {
        if (uiState.sentences.isNotEmpty() && uiState.currentIndex in uiState.sentences.indices) {
            listState.animateScrollToItem(
                index = uiState.currentIndex,
                scrollOffset = -180
            )
        }
    }

    Scaffold(
        containerColor = palette.pageBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val headerTitle = if (uiState.bookTitle.isNotBlank()) {
                            uiState.bookTitle
                        } else {
                            "Đang đọc sách (${uiState.currentIndex + 1}/${uiState.sentences.size.coerceAtLeast(1)})"
                        }
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val rateText = String.format(Locale.US, "%.2fx", uiState.settings.speechRate)
                            if (uiState.settings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = " VieNeu ($activeVoiceName • $rateText)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "Hệ thống TTS ($rateText)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Trở về")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { txtPickerLauncher.launch(arrayOf("text/plain", "text/*")) }
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Mở file .TXT")
                    }
                    IconButton(onClick = onDecreaseFontSize) {
                        Text("A-", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onIncreaseFontSize) {
                        Text("A+", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Cài đặt giọng đọc")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                )
            )
        },
        bottomBar = {
            StickyPlaybackBar(
                currentIndex = uiState.currentIndex,
                totalSentences = uiState.sentences.size,
                playbackStatus = uiState.playbackStatus,
                speechRate = uiState.settings.speechRate,
                isVieNeu = uiState.settings.engineType == TtsEngineType.VIENEU_ON_DEVICE,
                vieneuStatus = uiState.vieneuModelStatus,
                downloadProgress = uiState.vieneuDownloadProgress,
                isSynthesizing = uiState.isSynthesizing,
                bufferedCount = uiState.prebufferedIndices.size,
                onSeekToSentence = onSeekToSentence,
                onPrevious = onPreviousSentence,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNextSentence,
                onOpenSettings = { showSettingsSheet = true }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.pageBg)
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = uiState.sentences,
                key = { index, _ -> index }
            ) { index, sentence ->
                val isCurrent = index == uiState.currentIndex
                val isPrebuffered = uiState.prebufferedIndices.contains(index)
                val isVieNeu = uiState.settings.engineType == TtsEngineType.VIENEU_ON_DEVICE

                SentenceItemCard(
                    sentence = sentence,
                    index = index,
                    isCurrent = isCurrent,
                    isPrebuffered = isPrebuffered && isVieNeu,
                    fontSizeSp = uiState.fontSizeSp,
                    palette = palette,
                    onClick = { onSeekToSentence(index) }
                )
            }
        }
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = uiState.settings,
            readingTheme = uiState.readingTheme,
            availableEngines = uiState.availableEngines,
            availableSystemVoices = uiState.availableSystemVoices,
            vieneuStatus = uiState.vieneuModelStatus,
            downloadProgress = uiState.vieneuDownloadProgress,
            onSpeechRateChange = onSpeechRateChange,
            onPitchChange = onPitchChange,
            onSentencePauseChange = onSentencePauseChange,
            onWarmVoiceFilterChange = onWarmVoiceFilterChange,
            onBackgroundPlaybackChange = onBackgroundPlaybackChange,
            onReadingThemeChange = onReadingThemeChange,
            onResetVoiceDefaults = onResetVoiceDefaults,
            onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
            onEngineSelected = onEngineSelected,
            onSystemVoiceSelected = onSystemVoiceSelected,
            onEngineTypeSelected = onEngineTypeSelected,
            onVieNeuVoiceSelected = onVieNeuVoiceSelected,
            onLookaheadBufferSizeChange = onLookaheadBufferSizeChange,
            onDownloadVieNeuModel = onDownloadVieNeuModel,
            onDismissRequest = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun SentenceItemCard(
    sentence: String,
    index: Int,
    isCurrent: Boolean,
    isPrebuffered: Boolean,
    fontSizeSp: Float,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isCurrent) palette.activeCardBg else palette.cardBg,
        label = "SentenceBgColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) palette.activeTextPrimary else palette.textPrimary,
        label = "SentenceTextColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        border = if (isCurrent) BorderStroke(1.5.dp, palette.activeBorder) else null,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isCurrent) 3.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 10.dp, top = 3.dp)
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = (fontSizeSp * 0.68f).sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) palette.activeBorder else palette.textSecondary
                )

                if (isPrebuffered && !isCurrent) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32))
                    )
                }
            }

            Text(
                text = sentence,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.5f).sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun StickyPlaybackBar(
    currentIndex: Int,
    totalSentences: Int,
    playbackStatus: PlaybackStatus,
    speechRate: Float,
    isVieNeu: Boolean,
    vieneuStatus: VieNeuModelStatus,
    downloadProgress: Float,
    isSynthesizing: Boolean,
    bufferedCount: Int,
    onSeekToSentence: (Int) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPosition by remember(currentIndex) { mutableFloatStateOf(currentIndex.toFloat()) }

    val remainingSentences = (totalSentences - currentIndex - 1).coerceAtLeast(0)
    val estMinutesLeft = ((remainingSentences * 4.5f) / speechRate.coerceAtLeast(0.5f) / 60f).roundToInt()

    Surface(
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (totalSentences > 1) {
                Slider(
                    value = if (isDraggingSlider) sliderPosition else currentIndex.toFloat(),
                    onValueChange = {
                        isDraggingSlider = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        isDraggingSlider = false
                        onSeekToSentence(sliderPosition.roundToInt().coerceIn(0, totalSentences - 1))
                    },
                    valueRange = 0f..(totalSentences - 1).toFloat().coerceAtLeast(1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenSettings() }
                ) {
                    val displayedIdx = if (isDraggingSlider) sliderPosition.roundToInt() + 1 else currentIndex + 1
                    Text(
                        text = "Câu $displayedIdx / $totalSentences • Còn ~${estMinutesLeft.coerceAtLeast(1)} phút",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isVieNeu) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = if (isSynthesizing || vieneuStatus != VieNeuModelStatus.READY) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0xFF2E7D32)
                                }
                            )
                            val statusLabel = when {
                                vieneuStatus == VieNeuModelStatus.DOWNLOADING ->
                                    " Đang tải VieNeu AI: ${(downloadProgress * 100).toInt()}%"
                                vieneuStatus != VieNeuModelStatus.READY ->
                                    " Chạm để tải gói VieNeu AI (~256MB)"
                                isSynthesizing ->
                                    " Đang tổng hợp ONNX..."
                                else ->
                                    " RAM đệm: $bufferedCount câu sẵn sàng"
                            }
                            Text(
                                text = statusLabel,
                                fontSize = 11.sp,
                                color = if (isSynthesizing || vieneuStatus != VieNeuModelStatus.READY) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0xFF2E7D32)
                                }
                            )
                        }
                    } else {
                        Text(
                            text = "Chạm để chỉnh giọng & tốc độ",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onPrevious,
                        enabled = currentIndex > 0
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Câu trước"
                        )
                    }

                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(50.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (playbackStatus == PlaybackStatus.PLAYING) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = if (playbackStatus == PlaybackStatus.PLAYING) "Tạm dừng" else "Tiếp tục đọc",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = onNext,
                        enabled = currentIndex < totalSentences - 1
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Câu kế tiếp"
                        )
                    }
                }
            }
        }
    }
}
