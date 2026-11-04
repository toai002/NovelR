package com.novelr.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.ReaderUiState
import com.novelr.reader.model.ReadingTheme
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.vieneu.VieNeuConstants
import com.novelr.reader.util.ReaderParagraph
import com.novelr.reader.util.SentenceSpan
import java.util.Locale
import kotlin.math.roundToInt

private data class BookPagePalette(
    val pageBg: Color,
    val headerBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val activeHighlightBg: Color,
    val activeTextPrimary: Color,
    val prebufferedHighlightBg: Color,
    val accentBar: Color,
    val dividerColor: Color
)

@Composable
private fun rememberBookPagePalette(theme: ReadingTheme): BookPagePalette {
    val scheme = MaterialTheme.colorScheme
    return remember(theme, scheme) {
        when (theme) {
            ReadingTheme.SEPIA -> BookPagePalette(
                pageBg = Color(0xFFF7F0E2),
                headerBg = Color(0xFFEDE2CE),
                textPrimary = Color(0xFF2B2118),
                textSecondary = Color(0xFF786655),
                activeHighlightBg = Color(0xFFE6C896),
                activeTextPrimary = Color(0xFF1A1108),
                prebufferedHighlightBg = Color(0xFFEFE6D4),
                accentBar = Color(0xFF9C5A25),
                dividerColor = Color(0xFFE2D5BE)
            )
            ReadingTheme.DARK -> BookPagePalette(
                pageBg = Color(0xFF121418),
                headerBg = Color(0xFF1A1D24),
                textPrimary = Color(0xFFD6DAE2),
                textSecondary = Color(0xFF8B94A3),
                activeHighlightBg = Color(0xFF243B5C),
                activeTextPrimary = Color(0xFFFFFFFF),
                prebufferedHighlightBg = Color(0xFF1B2432),
                accentBar = Color(0xFF60A5FA),
                dividerColor = Color(0xFF232832)
            )
            ReadingTheme.LIGHT -> BookPagePalette(
                pageBg = Color(0xFFFAFBFD),
                headerBg = Color(0xFFEEF1F6),
                textPrimary = Color(0xFF1E232A),
                textSecondary = Color(0xFF646E7C),
                activeHighlightBg = Color(0xFFD6E6FF),
                activeTextPrimary = Color(0xFF0B2545),
                prebufferedHighlightBg = Color(0xFFF0F5FF),
                accentBar = scheme.primary,
                dividerColor = Color(0xFFE4E8F0)
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
    onTapSentence: (Int) -> Unit,
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
    val palette = rememberBookPagePalette(uiState.readingTheme)

    // Đảm bảo luôn có danh sách đoạn văn hiển thị liền mạch ngay cả khi chỉ có sentences
    val displayParagraphs = remember(uiState.paragraphs, uiState.sentences) {
        if (uiState.paragraphs.isNotEmpty()) {
            uiState.paragraphs
        } else {
            uiState.sentences.mapIndexed { idx, sent ->
                ReaderParagraph(
                    paragraphIndex = idx,
                    fullText = sent,
                    spans = listOf(
                        SentenceSpan(
                            sentenceIndex = idx,
                            text = sent,
                            startInParagraph = 0,
                            endInParagraph = sent.length
                        )
                    )
                )
            }
        }
    }

    val activeParagraphIndex = remember(displayParagraphs, uiState.currentIndex) {
        displayParagraphs.indexOfFirst { it.containsSentence(uiState.currentIndex) }.coerceAtLeast(0)
    }

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

    // Tự động cuộn trang sách tới đoạn văn đang có câu được đọc
    LaunchedEffect(activeParagraphIndex, uiState.currentIndex) {
        if (displayParagraphs.isNotEmpty() && activeParagraphIndex in displayParagraphs.indices) {
            listState.animateScrollToItem(
                index = activeParagraphIndex,
                scrollOffset = -120
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
                            "Trang đọc sách"
                        }
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
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
                                    tint = palette.accentBar
                                )
                                Text(
                                    text = " VieNeu ($activeVoiceName • $rateText) • Chạm vào chữ để đọc",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accentBar,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    text = "Hệ thống ($rateText) • Chạm vào chữ bất kỳ để đọc",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Trở về",
                            tint = palette.textPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { txtPickerLauncher.launch(arrayOf("text/plain", "text/*")) }
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "Mở file .TXT",
                            tint = palette.textPrimary
                        )
                    }
                    IconButton(onClick = onDecreaseFontSize) {
                        Text("A-", fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    }
                    IconButton(onClick = onIncreaseFontSize) {
                        Text("A+", fontWeight = FontWeight.Bold, color = palette.textPrimary)
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Cài đặt giọng đọc",
                            tint = palette.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.headerBg
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
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(
                items = displayParagraphs,
                key = { it.paragraphIndex }
            ) { paragraph ->
                val isParagraphActive = paragraph.containsSentence(uiState.currentIndex)

                BookParagraphView(
                    paragraph = paragraph,
                    currentSentenceIndex = uiState.currentIndex,
                    prebufferedIndices = if (uiState.settings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
                        uiState.prebufferedIndices
                    } else {
                        emptySet()
                    },
                    isParagraphActive = isParagraphActive,
                    fontSizeSp = uiState.fontSizeSp,
                    palette = palette,
                    onSentenceTapped = onTapSentence
                )
            }

            item {
                Spacer(modifier = Modifier.height(36.dp))
                HorizontalDivider(color = palette.dividerColor)
                Spacer(modifier = Modifier.height(12.dp))
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

/**
 * Hiển thị một đoạn văn liền mạch giữ nguyên cấu trúc câu chữ như trang sách thật.
 * Việc chia câu nằm ngầm trong [ReaderParagraph.spans]:
 * - Chỉ tô sáng (Highlight) câu đang được đọc ngay trong dòng văn bản.
 * - Khi người dùng nhấp vào bất kỳ chữ nào trong đoạn văn, ánh xạ tọa độ chạm -> chỉ số câu ngầm -> đọc ngay câu đó.
 */
@Composable
private fun BookParagraphView(
    paragraph: ReaderParagraph,
    currentSentenceIndex: Int,
    prebufferedIndices: Set<Int>,
    isParagraphActive: Boolean,
    fontSizeSp: Float,
    palette: BookPagePalette,
    onSentenceTapped: (Int) -> Unit
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val annotatedParagraph: AnnotatedString = remember(
        paragraph,
        currentSentenceIndex,
        prebufferedIndices,
        palette
    ) {
        buildAnnotatedString {
            append(paragraph.fullText)
            for (span in paragraph.spans) {
                val start = span.startInParagraph.coerceIn(0, paragraph.fullText.length)
                val end = span.endInParagraph.coerceIn(start, paragraph.fullText.length)
                if (start >= end) continue

                when {
                    span.sentenceIndex == currentSentenceIndex -> {
                        addStyle(
                            style = SpanStyle(
                                background = palette.activeHighlightBg,
                                color = palette.activeTextPrimary,
                                fontWeight = FontWeight.Medium
                            ),
                            start = start,
                            end = end
                        )
                    }
                    prebufferedIndices.contains(span.sentenceIndex) -> {
                        addStyle(
                            style = SpanStyle(
                                background = palette.prebufferedHighlightBg
                            ),
                            start = start,
                            end = end
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Thanh chỉ báo mảnh tinh tế bên lề trái cho đoạn văn đang được đọc
        Box(
            modifier = Modifier
                .width(3.dp)
                .padding(top = 4.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isParagraphActive) palette.accentBar else Color.Transparent)
        )

        Spacer(modifier = Modifier.width(if (isParagraphActive) 10.dp else 0.dp))

        Text(
            text = annotatedParagraph,
            style = TextStyle(
                color = palette.textPrimary,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.65f).sp,
                textAlign = TextAlign.Justify,
                textIndent = TextIndent(firstLine = (fontSizeSp * 1.2f).sp)
            ),
            onTextLayout = { result ->
                textLayoutResult = result
            },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(paragraph) {
                    detectTapGestures { tapOffset ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val charOffset = layout.getOffsetForPosition(tapOffset)
                        val tappedSentenceIdx = paragraph.findSentenceIndexAtCharOffset(charOffset)
                        if (tappedSentenceIdx != null) {
                            onSentenceTapped(tappedSentenceIdx)
                        }
                    }
                }
        )
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
                            text = "Chạm vào chữ trong bài để đọc câu đó",
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
