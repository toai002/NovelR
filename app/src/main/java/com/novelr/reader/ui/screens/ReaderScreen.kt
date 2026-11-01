package com.novelr.reader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatSize
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.novelr.reader.model.TtsEngineType
import com.novelr.reader.model.VieNeuModelStatus

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
    onSpeechRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSentencePauseChange: (Int) -> Unit,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onEngineSelected: (String) -> Unit,
    onEngineTypeSelected: (TtsEngineType) -> Unit,
    onVieNeuVoiceSelected: (String) -> Unit,
    onLookaheadBufferSizeChange: (Int) -> Unit,
    onDownloadVieNeuModel: () -> Unit
) {
    val listState = rememberLazyListState()
    var showSettingsSheet by remember { mutableStateOf(false) }

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
                scrollOffset = -150
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val headerTitle = if (uiState.bookTitle.isNotBlank()) {
                            uiState.bookTitle
                        } else {
                            "Đang đọc (${uiState.currentIndex + 1}/${uiState.sentences.size.coerceAtLeast(1)})"
                        }
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (uiState.settings.engineType == TtsEngineType.VIENEU_ON_DEVICE) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (uiState.settings.backgroundPlayback) {
                                        " VieNeu AI • Chạy nền BẬT"
                                    } else {
                                        " VieNeu AI (On-Device)"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = if (uiState.settings.backgroundPlayback) {
                                        "Hệ thống TTS • Chạy nền BẬT"
                                    } else {
                                        "Hệ thống TTS"
                                    },
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
                        Icon(Icons.Default.FolderOpen, contentDescription = "Mở file .TXT khác")
                    }
                    IconButton(onClick = onDecreaseFontSize) {
                        Text("A-", fontWeight = FontWeight.Bold)
                    }
                    Icon(
                        imageVector = Icons.Default.FormatSize,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    IconButton(onClick = onIncreaseFontSize) {
                        Text("A+", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Cài đặt giọng đọc")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            StickyPlaybackBar(
                currentIndex = uiState.currentIndex,
                totalSentences = uiState.sentences.size,
                playbackStatus = uiState.playbackStatus,
                isVieNeu = uiState.settings.engineType == TtsEngineType.VIENEU_ON_DEVICE,
                vieneuStatus = uiState.vieneuModelStatus,
                downloadProgress = uiState.vieneuDownloadProgress,
                isSynthesizing = uiState.isSynthesizing,
                bufferedCount = uiState.prebufferedIndices.size,
                onPrevious = onPreviousSentence,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNextSentence
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    onClick = { onSeekToSentence(index) }
                )
            }
        }
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = uiState.settings,
            availableEngines = uiState.availableEngines,
            vieneuStatus = uiState.vieneuModelStatus,
            downloadProgress = uiState.vieneuDownloadProgress,
            onSpeechRateChange = onSpeechRateChange,
            onPitchChange = onPitchChange,
            onSentencePauseChange = onSentencePauseChange,
            onBackgroundPlaybackChange = onBackgroundPlaybackChange,
            onRequestIgnoreBatteryOptimizations = onRequestIgnoreBatteryOptimizations,
            onEngineSelected = onEngineSelected,
            onEngineTypeSelected = onEngineTypeSelected,
            onVieNeuVoiceSelected = onVieNeuVoiceSelected,
            onLookaheadBufferSizeChange = onLookaheadBufferSizeChange,
            onDownloadVieNeuModel = onDownloadVieNeuModel,
            onDismissRequest = { showSettingsSheet = false }
        )
    }
}

@Composable
fun SentenceItemCard(
    sentence: String,
    index: Int,
    isCurrent: Boolean,
    isPrebuffered: Boolean,
    fontSizeSp: Float,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "SentenceBgColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "SentenceTextColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isCurrent) 4.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 10.dp, top = 2.dp)
            ) {
                Text(
                    text = "${index + 1}.",
                    fontSize = (fontSizeSp * 0.75f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )

                if (isPrebuffered && !isCurrent) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32))
                    )
                }
            }

            Text(
                text = sentence,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.45f).sp,
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
    isVieNeu: Boolean,
    vieneuStatus: VieNeuModelStatus,
    downloadProgress: Float,
    isSynthesizing: Boolean,
    bufferedCount: Int,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val progress = if (totalSentences > 0) {
        (currentIndex + 1).toFloat() / totalSentences.toFloat()
    } else {
        0f
    }

    Surface(
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Câu ${currentIndex + 1} / $totalSentences",
                        style = MaterialTheme.typography.labelLarge,
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
                                    " Cần tải gói VieNeu AI (~256MB)"
                                isSynthesizing ->
                                    " Đang tổng hợp ONNX..."
                                else ->
                                    " RAM đệm: $bufferedCount câu"
                            }
                            Text(
                                text = statusLabel,
                                fontSize = 10.sp,
                                color = if (isSynthesizing || vieneuStatus != VieNeuModelStatus.READY) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0xFF2E7D32)
                                }
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                        modifier = Modifier.size(52.dp),
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
                            modifier = Modifier.size(30.dp)
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
