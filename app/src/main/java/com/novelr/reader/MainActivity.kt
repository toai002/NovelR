package com.novelr.reader

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
    private var currentScreen by mutableStateOf(CurrentScreen.PASTE)
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
                            PasteScreen(
                                rawText = uiState.rawText,
                                onTextChanged = viewModel::onTextChanged,
                                onStartReading = {
                                    viewModel.startReading(
                                        onReady = { currentScreen = CurrentScreen.READER }
                                    )
                                }
                            )
                        }
                        CurrentScreen.READER -> {
                            BackHandler {
                                currentScreen = CurrentScreen.PASTE
                            }
                            ReaderScreen(
                                uiState = uiState,
                                onBack = { currentScreen = CurrentScreen.PASTE },
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onNextSentence = viewModel::nextSentence,
                                onPreviousSentence = viewModel::previousSentence,
                                onSeekToSentence = viewModel::seekToSentence,
                                onIncreaseFontSize = viewModel::increaseFontSize,
                                onDecreaseFontSize = viewModel::decreaseFontSize,
                                onSpeechRateChange = viewModel::updateSpeechRate,
                                onPitchChange = viewModel::updatePitch,
                                onEngineSelected = viewModel::selectEngine,
                                onEngineTypeSelected = viewModel::selectEngineType,
                                onVieNeuVoiceSelected = viewModel::selectVieNeuVoice,
                                onLookaheadBufferSizeChange = viewModel::updateLookaheadBuffer,
                                onDownloadVieNeuModel = viewModel::downloadVieNeuModel
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
