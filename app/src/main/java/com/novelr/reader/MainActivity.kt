package com.novelr.reader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.novelr.reader.service.TtsAudioService
import com.novelr.reader.ui.screens.PasteScreen
import com.novelr.reader.ui.screens.ReaderScreen
import com.novelr.reader.ui.viewmodel.ReaderViewModel

enum class CurrentScreen {
    PASTE,
    READER
}

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()
    // Mở thẳng vào giao diện đọc sách @Voice Aloud Reader (@reader) ngay khi khởi động
    private var currentScreen by mutableStateOf(CurrentScreen.READER)
    private var serviceBinder: TtsAudioService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TtsAudioService.LocalBinder
            binder?.getService()?.let { ttsService ->
                serviceBinder = ttsService
                isServiceBound = true
                viewModel.attachService(ttsService)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            isServiceBound = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission granted or rejected */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start & Bind TTS Foreground Service
        val serviceIntent = Intent(this, TtsAudioService::class.java)
        startService(serviceIntent)
        isServiceBound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val uiState by viewModel.uiState.collectAsState()

                    when (currentScreen) {
                        CurrentScreen.PASTE -> {
                            BackHandler {
                                currentScreen = CurrentScreen.READER
                            }
                            PasteScreen(
                                rawText = uiState.rawText,
                                bookTitle = uiState.bookTitle,
                                activeSentenceCount = uiState.sentences.size,
                                activeSentenceIndex = uiState.currentIndex,
                                onResumeActiveSession = { currentScreen = CurrentScreen.READER },
                                onTextChanged = viewModel::onTextChanged,
                                onPickTxtFile = { uri ->
                                    viewModel.loadTxtFile(
                                        context = this@MainActivity,
                                        uri = uri,
                                        autoStartReading = true,
                                        onReady = { currentScreen = CurrentScreen.READER }
                                    )
                                },
                                onStartReading = {
                                    viewModel.startReading(
                                        onReady = { currentScreen = CurrentScreen.READER }
                                    )
                                }
                            )
                        }
                        CurrentScreen.READER -> {
                            ReaderScreen(
                                uiState = uiState,
                                onOpenTextEditor = { currentScreen = CurrentScreen.PASTE },
                                onPasteClipboardText = { clipText ->
                                    viewModel.loadTextContent(
                                        raw = clipText,
                                        title = "Văn bản từ Bộ nhớ tạm",
                                        autoPlay = true
                                    )
                                },
                                onPickTxtFile = { uri ->
                                    viewModel.loadTxtFile(
                                        context = this@MainActivity,
                                        uri = uri,
                                        autoStartReading = true,
                                        onReady = { currentScreen = CurrentScreen.READER }
                                    )
                                },
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onNextSentence = viewModel::nextSentence,
                                onPreviousSentence = viewModel::previousSentence,
                                onSeekToSentence = viewModel::seekToSentence,
                                onTapSentence = viewModel::tapSentenceToRead,
                                onIncreaseFontSize = viewModel::increaseFontSize,
                                onDecreaseFontSize = viewModel::decreaseFontSize,
                                onReadingThemeChange = viewModel::updateReadingTheme,
                                onSpeechRateChange = viewModel::updateSpeechRate,
                                onPitchChange = viewModel::updatePitch,
                                onSentencePauseChange = viewModel::updateSentencePause,
                                onWarmVoiceFilterChange = viewModel::updateWarmVoiceFilter,
                                onBackgroundPlaybackChange = viewModel::updateBackgroundPlayback,
                                onHeadsetAutoPlayPauseChange = viewModel::updateHeadsetAutoPlayPause,
                                onResetVoiceDefaults = viewModel::resetVoiceSettingsToDefault,
                                onRequestIgnoreBatteryOptimizations = ::requestIgnoreBatteryOptimizations,
                                onEngineSelected = viewModel::selectEngine,
                                onSystemVoiceSelected = viewModel::selectSystemVoice,
                                onEngineTypeSelected = viewModel::selectEngineType,
                                onVieNeuVoiceSelected = viewModel::selectVieNeuVoice,
                                onPiperVoiceSelected = viewModel::selectPiperVoice,
                                onLookaheadBufferSizeChange = viewModel::updateLookaheadBuffer,
                                onDownloadVieNeuModel = viewModel::downloadVieNeuModel,
                                onDownloadPiperModel = viewModel::downloadPiperModel
                            )
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        super.onStop()
        serviceBinder?.onAppBackgrounded()
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
