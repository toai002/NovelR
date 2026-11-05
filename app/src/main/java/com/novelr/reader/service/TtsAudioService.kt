package com.novelr.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.novelr.reader.MainActivity
import com.novelr.reader.model.PlaybackStatus
import com.novelr.reader.model.TtsEngineType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class TtsAudioService : Service() {

    companion object {
        private const val TAG = "TtsAudioService"
        const val CHANNEL_ID = "novelr_tts_playback_channel"
        const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "novelr:TtsPlaybackWakeLock"

        const val ACTION_PLAY = "com.novelr.reader.ACTION_PLAY"
        const val ACTION_PAUSE = "com.novelr.reader.ACTION_PAUSE"
        const val ACTION_NEXT = "com.novelr.reader.ACTION_NEXT"
        const val ACTION_PREV = "com.novelr.reader.ACTION_PREV"
        const val ACTION_STOP = "com.novelr.reader.ACTION_STOP"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var ttsManager: TtsManager
        private set

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isForegroundActive = false
    private var resumeOnFocusGain = false
    private var isHeadsetReceiverRegistered = false

    /**
     * BroadcastReceiver giám sát trạng thái tai nghe (tai nghe dây 3.5mm/Type-C và tai nghe Bluetooth):
     * - Khi rút tai nghe (ACTION_AUDIO_BECOMING_NOISY hoặc HEADSET_PLUG state=0): Tự động tạm dừng để âm thanh không phát ra loa ngoài.
     * - Khi cắm/kết nối tai nghe (HEADSET_PLUG state=1 hoặc BluetoothHeadset/A2DP connected): Tự động bắt đầu đọc tiếp sách nếu có nội dung.
     */
    private val headsetEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!ttsManager.settings.value.headsetAutoPlayPause) return
            val action = intent?.action ?: return

            when (action) {
                // Sự kiện tiêu chuẩn Android khi tai nghe bất ngờ bị ngắt hoặc chuyển âm sang loa ngoài
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    Log.d(TAG, "Tai nghe bị ngắt kết nối (AUDIO_BECOMING_NOISY) -> Tạm dừng đọc")
                    resumeOnFocusGain = false
                    if (ttsManager.playbackStatus.value == PlaybackStatus.PLAYING) {
                        ttsManager.pause()
                    }
                }

                // Cắm hoặc rút tai nghe dây (3.5mm / USB-C DAC)
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    // state: 0 = unplugged, 1 = plugged
                    when (state) {
                        0 -> {
                            Log.d(TAG, "Tai nghe dây bị rút ra -> Tạm dừng đọc")
                            resumeOnFocusGain = false
                            if (ttsManager.playbackStatus.value == PlaybackStatus.PLAYING) {
                                ttsManager.pause()
                            }
                        }
                        1 -> {
                            Log.d(TAG, "Tai nghe dây được cắm vào -> Tự động bắt đầu đọc")
                            if (ttsManager.sentences.value.isNotEmpty() &&
                                (ttsManager.playbackStatus.value == PlaybackStatus.PAUSED ||
                                 ttsManager.playbackStatus.value == PlaybackStatus.IDLE)
                            ) {
                                ttsManager.play()
                            }
                        }
                    }
                }

                // Kết nối hoặc ngắt kết nối tai nghe Bluetooth (A2DP / HFP Profile)
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "Tai nghe Bluetooth đã kết nối -> Tự động bắt đầu đọc")
                            if (ttsManager.sentences.value.isNotEmpty() &&
                                (ttsManager.playbackStatus.value == PlaybackStatus.PAUSED ||
                                 ttsManager.playbackStatus.value == PlaybackStatus.IDLE)
                            ) {
                                ttsManager.play()
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "Tai nghe Bluetooth bị ngắt kết nối -> Tạm dừng đọc")
                            resumeOnFocusGain = false
                            if (ttsManager.playbackStatus.value == PlaybackStatus.PLAYING) {
                                ttsManager.pause()
                            }
                        }
                    }
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                ttsManager.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (ttsManager.playbackStatus.value == PlaybackStatus.PLAYING) {
                    resumeOnFocusGain = true
                    ttsManager.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    ttsManager.play()
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): TtsAudioService = this@TtsAudioService
    }

    override fun onCreate() {
        super.onCreate()
        ttsManager = TtsManager(applicationContext, serviceScope)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            setReferenceCounted(false)
        }

        createNotificationChannel()
        initMediaSession()
        registerHeadsetReceiver()
        observePlaybackState()
    }

    private fun registerHeadsetReceiver() {
        if (!isHeadsetReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            registerReceiver(headsetEventReceiver, filter)
            isHeadsetReceiverRegistered = true
            Log.d(TAG, "Đã đăng ký HeadsetEventReceiver (Dây & Bluetooth Auto Play/Pause)")
        }
    }

    private fun unregisterHeadsetReceiver() {
        if (isHeadsetReceiverRegistered) {
            try {
                unregisterReceiver(headsetEventReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi hủy đăng ký HeadsetReceiver: ${e.message}")
            }
            isHeadsetReceiverRegistered = false
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> ttsManager.play()
            ACTION_PAUSE -> {
                resumeOnFocusGain = false
                ttsManager.pause()
            }
            ACTION_NEXT -> ttsManager.next()
            ACTION_PREV -> ttsManager.previous()
            ACTION_STOP -> {
                resumeOnFocusGain = false
                ttsManager.stop()
                releaseWakeLock()
                stopForegroundServiceCleanly(removeNotification = true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Được gọi từ MainActivity.onStop() khi người dùng tắt màn hình hoặc thoát ra màn hình chính.
     * Nếu bật Chế độ chạy nền (backgroundPlayback = true), giữ Foreground Service + WakeLock để đọc liên tục.
     * Nếu tắt Chế độ chạy nền, tự động tạm dừng đọc.
     */
    fun onAppBackgrounded() {
        if (!ttsManager.settings.value.backgroundPlayback &&
            ttsManager.playbackStatus.value == PlaybackStatus.PLAYING
        ) {
            ttsManager.pause()
        } else if (ttsManager.playbackStatus.value == PlaybackStatus.PLAYING) {
            acquireWakeLock()
            startForegroundServiceWithNotification()
        }
    }

    private fun acquireWakeLock() {
        try {
            val wl = wakeLock ?: return
            if (!wl.isHeld) {
                // Giữ CPU hoạt động tối đa 6 giờ liên tục khi tắt màn hình đọc sách
                wl.acquire(6 * 60 * 60 * 1000L)
                Log.d(TAG, "Đã bật CPU WakeLock cho chế độ đọc chạy nền")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không thể bật WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock ?: return
            if (wl.isHeld) {
                wl.release()
                Log.d(TAG, "Đã giải phóng CPU WakeLock")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không thể giải phóng WakeLock: ${e.message}")
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "NovelRTtsSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (requestAudioFocus()) {
                        ttsManager.play()
                    }
                }

                override fun onPause() {
                    resumeOnFocusGain = false
                    ttsManager.pause()
                }

                override fun onSkipToNext() {
                    ttsManager.next()
                }

                override fun onSkipToPrevious() {
                    ttsManager.previous()
                }

                override fun onStop() {
                    resumeOnFocusGain = false
                    abandonAudioFocus()
                    ttsManager.stop()
                    releaseWakeLock()
                    stopForegroundServiceCleanly(removeNotification = true)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    private fun observePlaybackState() {
        ttsManager.playbackStatus.onEach { status ->
            updateMediaSessionState(status)
            updateMediaSessionMetadata()
            when (status) {
                PlaybackStatus.PLAYING -> {
                    requestAudioFocus()
                    acquireWakeLock()
                    startForegroundServiceWithNotification()
                }
                PlaybackStatus.PAUSED -> {
                    releaseWakeLock()
                    if (isForegroundActive) {
                        updateNotification()
                    }
                }
                PlaybackStatus.COMPLETED -> {
                    releaseWakeLock()
                    abandonAudioFocus()
                    if (isForegroundActive) {
                        stopForegroundServiceCleanly(removeNotification = false)
                        updateNotification()
                    }
                }
                PlaybackStatus.IDLE -> {
                    releaseWakeLock()
                    abandonAudioFocus()
                    if (isForegroundActive) {
                        stopForegroundServiceCleanly(removeNotification = true)
                    }
                }
            }
        }.launchIn(serviceScope)

        ttsManager.currentIndex.onEach {
            updateMediaSessionMetadata()
            if (isForegroundActive && (
                ttsManager.playbackStatus.value == PlaybackStatus.PLAYING ||
                ttsManager.playbackStatus.value == PlaybackStatus.PAUSED
            )) {
                updateNotification()
            }
        }.launchIn(serviceScope)
    }

    private fun updateMediaSessionMetadata() {
        val currentIndex = ttsManager.currentIndex.value
        val total = ttsManager.sentences.value.size
        val displayIndex = if (total > 0) (currentIndex + 1).coerceAtMost(total) else 0
        val currentSentence = ttsManager.sentences.value.getOrNull(currentIndex) ?: "NovelR - Đọc Sách TTS"
        val rawTitle = ttsManager.bookTitle.value.ifBlank { "NovelR AudioBook" }
        val engineLabel = when (ttsManager.settings.value.engineType) {
            TtsEngineType.PIPER_ON_DEVICE -> "Piper Rõ Chữ • Câu $displayIndex/$total"
            TtsEngineType.VIENEU_ON_DEVICE -> "VieNeu AI • Câu $displayIndex/$total"
            TtsEngineType.NATIVE_SYSTEM -> "Hệ thống • Câu $displayIndex/$total"
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSentence)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "$rawTitle ($engineLabel)")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, rawTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, displayIndex.toLong())
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, total.toLong())
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundActive = true
    }

    private fun stopForegroundServiceCleanly(removeNotification: Boolean) {
        val flags = if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
        stopForeground(flags)
        if (removeNotification) {
            isForegroundActive = false
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = ttsManager.playbackStatus.value == PlaybackStatus.PLAYING
        val currentIndex = ttsManager.currentIndex.value
        val total = ttsManager.sentences.value.size
        val displayIndex = if (total > 0) (currentIndex + 1).coerceAtMost(total) else 0
        val currentSentence = ttsManager.sentences.value.getOrNull(currentIndex) ?: "Đang đọc sách..."
        val titlePrefix = ttsManager.bookTitle.value.ifBlank { "NovelR" }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TtsAudioService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TtsAudioService::class.java).apply {
                action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, TtsAudioService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, TtsAudioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$titlePrefix ($displayIndex / $total)")
            .setContentText(currentSentence)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Câu trước", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Tạm dừng" else "Đọc tiếp", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Câu sau", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateMediaSessionState(status: PlaybackStatus) {
        val playbackState = when (status) {
            PlaybackStatus.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackStatus.COMPLETED -> PlaybackStateCompat.STATE_STOPPED
            PlaybackStatus.IDLE -> PlaybackStateCompat.STATE_NONE
        }

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }

            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Book Reader Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị câu đang đọc và nút điều khiển trên màn hình khóa."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        unregisterHeadsetReceiver()
        releaseWakeLock()
        abandonAudioFocus()
        mediaSession.release()
        ttsManager.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
