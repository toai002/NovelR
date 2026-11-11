package com.novelr.reader.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.novelr.reader.model.TtsSettings
import com.novelr.reader.model.VieNeuModelStatus
import com.novelr.reader.service.edgetts.EdgeTtsConstants
import com.novelr.reader.service.piper.PiperConstants
import com.novelr.reader.service.vieneu.VieNeuConstants
import com.novelr.reader.util.ReaderParagraph
import com.novelr.reader.util.SentenceSpan
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bảng màu lấy cảm hứng từ @Voice Aloud Reader (@reader):
 * - Nền đọc sách dịu mắt (Sepia / Dark / Light).
 * - Câu đang đọc được tô sáng nổi bật kiểu @Voice (#FFE082 vàng ấm trên nền sáng/Sepia, #243B5C xanh đậm trên nền tối).
 */
private data class AtReaderPalette(
    val pageBg: Color,
    val toolbarBg: Color,
    val consoleBg: Color,
    val drawerBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val activeHighlightBg: Color,
    val activeTextPrimary: Color,
    val prebufferedHighlightBg: Color,
    val accentColor: Color,
    val dividerColor: Color
)

@Composable
private fun rememberAtReaderPalette(theme: ReadingTheme): AtReaderPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(theme, scheme) {
        when (theme) {
            ReadingTheme.SEPIA -> AtReaderPalette(
                pageBg = Color(0xFFF6EFE2),
                toolbarBg = Color(0xFFEAE0CD),
                consoleBg = Color(0xFFEAE0CD),
                drawerBg = Color(0xFFF1E8D7),
                textPrimary = Color(0xFF281E15),
                textSecondary = Color(0xFF6E5D4F),
                activeHighlightBg = Color(0xFFFFDF7E),
                activeTextPrimary = Color(0xFF140D06),
                prebufferedHighlightBg = Color(0xFFEDE2CE),
                accentColor = Color(0xFF9C521B),
                dividerColor = Color(0xFFDCD0BA)
            )
            ReadingTheme.DARK -> AtReaderPalette(
                pageBg = Color(0xFF121418),
                toolbarBg = Color(0xFF1B1E26),
                consoleBg = Color(0xFF1B1E26),
                drawerBg = Color(0xFF222631),
                textPrimary = Color(0xFFDADEE6),
                textSecondary = Color(0xFF8E97A8),
                activeHighlightBg = Color(0xFF28456E),
                activeTextPrimary = Color(0xFFFFFFFF),
                prebufferedHighlightBg = Color(0xFF1A2433),
                accentColor = Color(0xFF64B5F6),
                dividerColor = Color(0xFF2B303C)
            )
            ReadingTheme.LIGHT -> AtReaderPalette(
                pageBg = Color(0xFFFAFAFC),
                toolbarBg = Color(0xFFEDF0F5),
                consoleBg = Color(0xFFEDF0F5),
                drawerBg = Color(0xFFF4F6FA),
                textPrimary = Color(0xFF1B2028),
                textSecondary = Color(0xFF5E6878),
                activeHighlightBg = Color(0xFFFFE57F),
                activeTextPrimary = Color(0xFF0F141C),
                prebufferedHighlightBg = Color(0xFFEBF2FF),
                accentColor = scheme.primary,
                dividerColor = Color(0xFFDCE1EB)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    onOpenTextEditor: () -> Unit,
    onPasteClipboardText: (String) -> Unit,
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
    onHeadsetAutoPlayPauseChange: (Boolean) -> Unit,
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
    onDownloadPiperModel: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showQuickSliders by remember { mutableStateOf(false) }

    val palette = rememberAtReaderPalette(uiState.readingTheme)

    // Đảm bảo luôn có danh sách đoạn văn hiển thị liền mạch giữ nguyên cấu trúc câu
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

    val activePiperVoiceName = remember(uiState.settings.selectedPiperVoice) {
        PiperConstants.findById(uiState.settings.selectedPiperVoice).name
    }

    val activeEdgeVoiceName = remember(uiState.settings.selectedEdgeVoice) {
        EdgeTtsConstants.findById(uiState.settings.selectedEdgeVoice).name
    }

    val txtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onPickTxtFile(uri)
        }
    }

    // Tự động cuộn trang sách tới đoạn văn đang có câu được đọc giống @Voice Aloud Reader
    LaunchedEffect(activeParagraphIndex, uiState.currentIndex) {
        if (displayParagraphs.isNotEmpty() && activeParagraphIndex in displayParagraphs.indices) {
            listState.animateScrollToItem(
                index = activeParagraphIndex,
                scrollOffset = -110
            )
        }
    }

    val totalSentences = uiState.sentences.size.coerceAtLeast(1)
    val progressPercent = (((uiState.currentIndex + 1).toFloat() / totalSentences.toFloat()) * 100f)
        .roundToInt()
        .coerceIn(0, 100)

    Scaffold(
        containerColor = palette.pageBg,
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable { onOpenTextEditor() }
                    ) {
                        val headerTitle = if (uiState.bookTitle.isNotBlank()) {
                            uiState.bookTitle
                        } else {
                            "@Reader • Truyện đang đọc"
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
                            when (uiState.settings.engineType) {
                                TtsEngineType.EDGE_ONLINE -> {
                                    Icon(
                                        imageVector = Icons.Default.CloudQueue,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = palette.accentColor
                                    )
                                    Text(
                                        text = " Edge TTS ($activeEdgeVoiceName • $rateText) • Câu ${uiState.currentIndex + 1}/${uiState.sentences.size} ($progressPercent%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TtsEngineType.PIPER_ON_DEVICE -> {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = palette.accentColor
                                    )
                                    Text(
                                        text = " Piper ($activePiperVoiceName • $rateText) • Câu ${uiState.currentIndex + 1}/${uiState.sentences.size} ($progressPercent%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TtsEngineType.VIENEU_ON_DEVICE -> {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = palette.accentColor
                                    )
                                    Text(
                                        text = " VieNeu ($activeVoiceName • $rateText) • Câu ${uiState.currentIndex + 1}/${uiState.sentences.size} ($progressPercent%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TtsEngineType.NATIVE_SYSTEM -> {
                                    Text(
                                        text = "Hệ thống ($rateText) • Câu ${uiState.currentIndex + 1}/${uiState.sentences.size} ($progressPercent%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    // 1. Nút Mở file .TXT trực tiếp trên thanh công cụ kiểu @Voice Aloud Reader
                    IconButton(
                        onClick = { txtPickerLauncher.launch(arrayOf("text/plain", "text/*")) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Mở file .TXT",
                            tint = palette.textPrimary
                        )
                    }
                    // 2. Nút Dán nhanh từ Clipboard và đọc ngay lập tức kiểu @Voice Aloud Reader
                    IconButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) {
                                onPasteClipboardText(clipText)
                                Toast.makeText(context, "Đã dán văn bản từ bộ nhớ tạm", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Bộ nhớ tạm trống, hãy mở trình nhập văn bản", Toast.LENGTH_SHORT).show()
                                onOpenTextEditor()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Dán và đọc từ bộ nhớ tạm",
                            tint = palette.textPrimary
                        )
                    }
                    // 3. Nút Mở trình Nhập / Chỉnh sửa văn bản đầy đủ
                    IconButton(onClick = onOpenTextEditor) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = "Nhập hoặc sửa văn bản",
                            tint = palette.textPrimary
                        )
                    }
                    // 4. Nút Cài đặt giọng & hệ thống
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Cài đặt chi tiết",
                            tint = palette.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.toolbarBg
                )
            )
        },
        bottomBar = {
            AtReaderBottomConsole(
                uiState = uiState,
                palette = palette,
                showQuickSliders = showQuickSliders,
                onToggleQuickSliders = { showQuickSliders = !showQuickSliders },
                onSeekToSentence = onSeekToSentence,
                onPrevious = onPreviousSentence,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNextSentence,
                onSpeechRateChange = onSpeechRateChange,
                onPitchChange = onPitchChange,
                onSentencePauseChange = onSentencePauseChange,
                onWarmVoiceFilterChange = onWarmVoiceFilterChange,
                onResetVoiceDefaults = onResetVoiceDefaults,
                onIncreaseFontSize = onIncreaseFontSize,
                onDecreaseFontSize = onDecreaseFontSize,
                onReadingThemeChange = onReadingThemeChange,
                onEngineTypeSelected = onEngineTypeSelected,
                onPiperVoiceSelected = onPiperVoiceSelected,
                onEdgeVoiceSelected = onEdgeVoiceSelected,
                onOpenFullSettings = { showSettingsSheet = true }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.pageBg)
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = displayParagraphs,
                key = { it.paragraphIndex }
            ) { paragraph ->
                val isParagraphActive = paragraph.containsSentence(uiState.currentIndex)

                BookParagraphView(
                    paragraph = paragraph,
                    currentSentenceIndex = uiState.currentIndex,
                    prebufferedIndices = if (uiState.settings.engineType == TtsEngineType.VIENEU_ON_DEVICE ||
                        uiState.settings.engineType == TtsEngineType.PIPER_ON_DEVICE ||
                        uiState.settings.engineType == TtsEngineType.EDGE_ONLINE
                    ) {
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
                Spacer(modifier = Modifier.height(32.dp))
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
            piperStatus = uiState.piperModelStatus,
            piperDownloadProgress = uiState.piperDownloadProgress,
            onSpeechRateChange = onSpeechRateChange,
            onPitchChange = onPitchChange,
            onSentencePauseChange = onSentencePauseChange,
            onWarmVoiceFilterChange = onWarmVoiceFilterChange,
            onBackgroundPlaybackChange = onBackgroundPlaybackChange,
            onHeadsetAutoPlayPauseChange = onHeadsetAutoPlayPauseChange,
            onReadingThemeChange = onReadingThemeChange,
            onResetVoiceDefaults = onResetVoiceDefaults,
            onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
            onEngineSelected = onEngineSelected,
            onSystemVoiceSelected = onSystemVoiceSelected,
            onEngineTypeSelected = onEngineTypeSelected,
            onVieNeuVoiceSelected = onVieNeuVoiceSelected,
            onPiperVoiceSelected = onPiperVoiceSelected,
            onEdgeVoiceSelected = onEdgeVoiceSelected,
            onLookaheadBufferSizeChange = onLookaheadBufferSizeChange,
            onDownloadVieNeuModel = onDownloadVieNeuModel,
            onDownloadPiperModel = onDownloadPiperModel,
            onDismissRequest = { showSettingsSheet = false }
        )
    }
}

/**
 * Hiển thị đoạn văn liền mạch chuẩn @Voice Aloud Reader (@reader):
 * - Văn bản giữ nguyên cấu trúc đoạn văn gốc.
 * - Các câu đã được tách ngầm bên dưới thông qua [ReaderParagraph.spans].
 * - Câu đang đọc được tô sáng bằng màu highlight đặc trưng ngay trong đoạn văn.
 * - Chạm tay vào bất kỳ chữ/câu nào sẽ ánh xạ tọa độ chạm -> chỉ số câu ngầm -> đọc ngay câu đó.
 */
@Composable
private fun BookParagraphView(
    paragraph: ReaderParagraph,
    currentSentenceIndex: Int,
    prebufferedIndices: Set<Int>,
    isParagraphActive: Boolean,
    fontSizeSp: Float,
    palette: AtReaderPalette,
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
        Box(
            modifier = Modifier
                .width(3.dp)
                .padding(top = 4.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isParagraphActive) palette.accentColor else Color.Transparent)
        )

        Spacer(modifier = Modifier.width(if (isParagraphActive) 8.dp else 0.dp))

        Text(
            text = annotatedParagraph,
            style = TextStyle(
                color = palette.textPrimary,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.62f).sp,
                textAlign = TextAlign.Justify,
                textIndent = TextIndent(firstLine = (fontSizeSp * 1.15f).sp)
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

/**
 * Bảng điều khiển phía dưới mang phong cách đặc trưng của @Voice Aloud Reader (@reader):
 * - Có khay trượt nhanh (Quick Sliders Drawer) ngay phía trên thanh Play/Pause để chỉnh trực tiếp
 *   Tốc độ (Rate), Cao độ (Pitch), Nghỉ câu (Pause), Cỡ chữ và Màu nền mà vẫn nhìn thấy trang sách.
 * - Thanh tiến độ câu + hàng nút điều khiển đối xứng: [🎚️ Chỉnh nhanh] [⏮] [▶/⏸] [⏭] [⚙️ Cài đặt].
 */
@Composable
private fun AtReaderBottomConsole(
    uiState: ReaderUiState,
    palette: AtReaderPalette,
    showQuickSliders: Boolean,
    onToggleQuickSliders: () -> Unit,
    onSeekToSentence: (Int) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSentencePauseChange: (Int) -> Unit,
    onWarmVoiceFilterChange: (Boolean) -> Unit,
    onResetVoiceDefaults: () -> Unit,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    onReadingThemeChange: (ReadingTheme) -> Unit,
    onEngineTypeSelected: (TtsEngineType) -> Unit,
    onPiperVoiceSelected: (String) -> Unit,
    onEdgeVoiceSelected: (String) -> Unit,
    onOpenFullSettings: () -> Unit
) {
    val totalSentences = uiState.sentences.size
    val currentIndex = uiState.currentIndex
    val settings = uiState.settings
    val isVieNeu = settings.engineType == TtsEngineType.VIENEU_ON_DEVICE
    val isPiper = settings.engineType == TtsEngineType.PIPER_ON_DEVICE
    val isEdge = settings.engineType == TtsEngineType.EDGE_ONLINE

    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPosition by remember(currentIndex) { mutableFloatStateOf(currentIndex.toFloat()) }

    val remainingSentences = (totalSentences - currentIndex - 1).coerceAtLeast(0)
    val estMinutesLeft = ((remainingSentences * 4.5f) / settings.speechRate.coerceAtLeast(0.5f) / 60f).roundToInt()

    Surface(
        shadowElevation = 14.dp,
        color = palette.consoleBg,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = palette.dividerColor)

            // Khay chỉnh nhanh Tốc độ / Cao độ / Nghỉ câu / Giao diện kiểu @Voice Aloud Reader
            AnimatedVisibility(
                visible = showQuickSliders,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.drawerBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // Hàng đầu: Chuyển nhanh Giọng Hệ Thống / Piper 73 Giọng / VieNeu AI + Nút Mặc định + Đóng khay
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            FilterChip(
                                selected = settings.engineType == TtsEngineType.NATIVE_SYSTEM,
                                onClick = { onEngineTypeSelected(TtsEngineType.NATIVE_SYSTEM) },
                                label = { Text("Hệ thống", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = isEdge,
                                onClick = { onEngineTypeSelected(TtsEngineType.EDGE_ONLINE) },
                                label = { Text("Edge TTS", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = isPiper,
                                onClick = { onEngineTypeSelected(TtsEngineType.PIPER_ON_DEVICE) },
                                label = { Text("Piper (73 giọng)", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = isVieNeu,
                                onClick = { onEngineTypeSelected(TtsEngineType.VIENEU_ON_DEVICE) },
                                label = { Text("VieNeu AI", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = settings.warmVoiceFilter,
                                onClick = { onWarmVoiceFilterChange(!settings.warmVoiceFilter) },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Chống chói", fontSize = 12.sp)
                                    }
                                }
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = onResetVoiceDefaults,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    Icons.Default.RestartAlt,
                                    contentDescription = "Mặc định",
                                    modifier = Modifier.size(15.dp),
                                    tint = palette.accentColor
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Mặc định", fontSize = 12.sp, color = palette.accentColor)
                            }
                            IconButton(
                                onClick = onToggleQuickSliders,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Thu gọn",
                                    tint = palette.textSecondary
                                )
                            }
                        }
                    }

                    // Nếu đang chọn Edge TTS, hiển thị thanh chọn nhanh 8 giọng Microsoft Edge Neural ngay trong khay
                    if (isEdge) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            EdgeTtsConstants.PRESET_VOICES.forEach { profile ->
                                FilterChip(
                                    selected = settings.selectedEdgeVoice == profile.id,
                                    onClick = { onEdgeVoiceSelected(profile.id) },
                                    label = { Text(profile.name, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    // Nếu đang chọn Piper (73 giọng), hiển thị thanh chọn nhanh giọng HD + VIVOS ngay trong khay
                    if (isPiper) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            PiperConstants.HD_PRESET_VOICES.forEach { profile ->
                                FilterChip(
                                    selected = settings.selectedPiperVoice == profile.id,
                                    onClick = { onPiperVoiceSelected(profile.id) },
                                    label = { Text(profile.name, fontSize = 11.sp) }
                                )
                            }
                            listOf(0, 2, 5, 11, 18, 24, 32, 45, 64).forEach { sid ->
                                val vid = "vivos_$sid"
                                val label = "VIVOS #${String.format(Locale.US, "%02d", sid + 1)}"
                                FilterChip(
                                    selected = settings.selectedPiperVoice == vid,
                                    onClick = { onPiperVoiceSelected(vid) },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Thanh trượt 1: Tốc độ đọc (Speech Rate) + chạm số để reset 1.0x
                    AtReaderQuickSliderRow(
                        label = "Tốc độ",
                        valueText = String.format(Locale.US, "%.2fx", settings.speechRate),
                        value = settings.speechRate,
                        valueRange = 0.5f..2.0f,
                        palette = palette,
                        onValueChange = onSpeechRateChange,
                        onResetClick = { onSpeechRateChange(TtsSettings.DEFAULT_SPEECH_RATE) }
                    )

                    // Thanh trượt 2: Cao độ (Pitch) + chạm số để reset 1.0x
                    AtReaderQuickSliderRow(
                        label = "Cao độ",
                        valueText = String.format(Locale.US, "%.2fx", settings.pitch),
                        value = settings.pitch,
                        valueRange = 0.5f..1.5f,
                        palette = palette,
                        onValueChange = onPitchChange,
                        onResetClick = { onPitchChange(TtsSettings.DEFAULT_PITCH) }
                    )

                    // Thanh trượt 3: Nghỉ chuyển câu (Pause) + chạm số để reset 550ms
                    AtReaderQuickSliderRow(
                        label = "Nghỉ câu",
                        valueText = "${settings.sentencePauseMs}ms",
                        value = settings.sentencePauseMs.toFloat(),
                        valueRange = 200f..1200f,
                        palette = palette,
                        onValueChange = { onSentencePauseChange(it.roundToInt()) },
                        onResetClick = { onSentencePauseChange(TtsSettings.DEFAULT_SENTENCE_PAUSE_MS) }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Hàng chỉnh nhanh Cỡ chữ (A- / A+) và Màu nền trang sách (Vàng dịu / Tối / Sáng)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = palette.consoleBg,
                                modifier = Modifier.clickable { onDecreaseFontSize() }
                            ) {
                                Text(
                                    text = "A-",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = palette.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                            Text(
                                text = "${uiState.fontSizeSp.toInt()}sp",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textSecondary
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = palette.consoleBg,
                                modifier = Modifier.clickable { onIncreaseFontSize() }
                            ) {
                                Text(
                                    text = "A+",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = palette.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ThemeCircleOption(
                                label = "Vàng",
                                bgColor = Color(0xFFF6EFE2),
                                textColor = Color(0xFF281E15),
                                selected = uiState.readingTheme == ReadingTheme.SEPIA,
                                accentColor = palette.accentColor,
                                onClick = { onReadingThemeChange(ReadingTheme.SEPIA) }
                            )
                            ThemeCircleOption(
                                label = "Tối",
                                bgColor = Color(0xFF121418),
                                textColor = Color(0xFFDADEE6),
                                selected = uiState.readingTheme == ReadingTheme.DARK,
                                accentColor = palette.accentColor,
                                onClick = { onReadingThemeChange(ReadingTheme.DARK) }
                            )
                            ThemeCircleOption(
                                label = "Sáng",
                                bgColor = Color(0xFFFAFAFC),
                                textColor = Color(0xFF1B2028),
                                selected = uiState.readingTheme == ReadingTheme.LIGHT,
                                accentColor = palette.accentColor,
                                onClick = { onReadingThemeChange(ReadingTheme.LIGHT) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = palette.dividerColor)
                }
            }

            // Phần điều khiển chính phía dưới của @Voice Aloud Reader
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Dòng thông tin vị trí câu + trạng thái bộ đệm + thanh kéo tiến độ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayedIdx = if (isDraggingSlider) sliderPosition.roundToInt() + 1 else currentIndex + 1
                    Text(
                        text = "Câu $displayedIdx/$totalSentences • Còn ~${estMinutesLeft.coerceAtLeast(1)} phút",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )

                    if (isEdge) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = if (uiState.isSynthesizing) palette.accentColor else Color(0xFF2E7D32)
                            )
                            val statusText = if (uiState.isSynthesizing) {
                                " Đang tải Edge..."
                            } else {
                                " Đệm: ${uiState.prebufferedIndices.size} câu"
                            }
                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = if (uiState.isSynthesizing) palette.accentColor else Color(0xFF2E7D32)
                            )
                        }
                    } else if (isPiper) {
                        val activeStatus = uiState.piperModelStatus
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = if (uiState.isSynthesizing || activeStatus != VieNeuModelStatus.READY) {
                                    palette.accentColor
                                } else {
                                    Color(0xFF2E7D32)
                                }
                            )
                            val statusText = when {
                                activeStatus == VieNeuModelStatus.DOWNLOADING ->
                                    " Tải Piper ${(uiState.piperDownloadProgress * 100).toInt()}%"
                                activeStatus != VieNeuModelStatus.READY ->
                                    " Chưa tải model Piper"
                                uiState.isSynthesizing ->
                                    " Đang gen Piper..."
                                else ->
                                    " Đệm: ${uiState.prebufferedIndices.size} câu"
                            }
                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = if (uiState.isSynthesizing || activeStatus != VieNeuModelStatus.READY) {
                                    palette.accentColor
                                } else {
                                    Color(0xFF2E7D32)
                                }
                            )
                        }
                    } else if (isVieNeu) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = if (uiState.isSynthesizing || uiState.vieneuModelStatus != VieNeuModelStatus.READY) {
                                    palette.accentColor
                                } else {
                                    Color(0xFF2E7D32)
                                }
                            )
                            val statusText = when {
                                uiState.vieneuModelStatus == VieNeuModelStatus.DOWNLOADING ->
                                    " Tải AI ${(uiState.vieneuDownloadProgress * 100).toInt()}%"
                                uiState.vieneuModelStatus != VieNeuModelStatus.READY ->
                                    " Chưa tải model AI"
                                uiState.isSynthesizing ->
                                    " Đang gen ONNX..."
                                else ->
                                    " Đệm: ${uiState.prebufferedIndices.size} câu"
                            }
                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = if (uiState.isSynthesizing || uiState.vieneuModelStatus != VieNeuModelStatus.READY) {
                                    palette.accentColor
                                } else {
                                    Color(0xFF2E7D32)
                                }
                            )
                        }
                    } else {
                        Text(
                            text = "Chạm vào câu bất kỳ trong sách để đọc",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }
                }

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
                            .height(22.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = palette.accentColor,
                            activeTrackColor = palette.accentColor,
                            inactiveTrackColor = palette.dividerColor
                        )
                    )
                }

                // Hàng 5 nút điều khiển chuẩn @Voice Aloud Reader:
                // [🎚️ Sliders]   [⏮ Câu trước]   [▶ / ⏸ Play]   [⏭ Câu tiếp]   [⚙️ Cài đặt]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 1. Nút bật/tắt Khay chỉnh nhanh Tốc độ/Cao độ (@Voice Quick Sliders)
                    IconButton(
                        onClick = onToggleQuickSliders,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (showQuickSliders) {
                                palette.accentColor.copy(alpha = 0.16f)
                            } else {
                                Color.Transparent
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Khay chỉnh nhanh tốc độ và giọng",
                            tint = if (showQuickSliders) palette.accentColor else palette.textPrimary
                        )
                    }

                    // 2. Nút Lùi câu trước
                    IconButton(
                        onClick = onPrevious,
                        enabled = currentIndex > 0
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Câu trước",
                            tint = if (currentIndex > 0) palette.textPrimary else palette.textSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // 3. Nút Play / Pause trung tâm nổi bật
                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = palette.accentColor
                        )
                    ) {
                        Icon(
                            imageVector = if (uiState.playbackStatus == PlaybackStatus.PLAYING) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = if (uiState.playbackStatus == PlaybackStatus.PLAYING) {
                                "Tạm dừng"
                            } else {
                                "Phát"
                            },
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    // 4. Nút Tiến câu tiếp theo
                    IconButton(
                        onClick = onNext,
                        enabled = currentIndex < totalSentences - 1
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Câu kế tiếp",
                            tint = if (currentIndex < totalSentences - 1) palette.textPrimary else palette.textSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // 5. Nút Mở bảng cài đặt giọng đọc & hệ thống đầy đủ
                    IconButton(onClick = onOpenFullSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Mở cài đặt đầy đủ",
                            tint = palette.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AtReaderQuickSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    palette: AtReaderPalette,
    onValueChange: (Float) -> Unit,
    onResetClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = palette.textPrimary,
            modifier = Modifier.width(62.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .weight(1f)
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = palette.accentColor,
                activeTrackColor = palette.accentColor,
                inactiveTrackColor = palette.dividerColor
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = palette.consoleBg,
            modifier = Modifier.clickable(onClick = onResetClick)
        ) {
            Text(
                text = valueText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = palette.accentColor,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ThemeCircleOption(
    label: String,
    bgColor: Color,
    textColor: Color,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accentColor else Color.Gray.copy(alpha = 0.35f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}
