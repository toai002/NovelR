package com.novelr.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.novelr.reader.MainActivity
import com.novelr.reader.model.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class TtsAudioService : Service() {

    companion object {
        const val CHANNEL_ID = "novelr_tts_playback_channel"
        const val NOTIFICATION_ID = 1001

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
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isForegroundActive = false
    private var resumeOnFocusGain = false

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

        createNotificationChannel()
        initMediaSession()
        observePlaybackState()
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
                stopForegroundServiceCleanly(removeNotification = true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
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
            when (status) {
                PlaybackStatus.PLAYING -> {
                    requestAudioFocus()
                    startForegroundServiceWithNotification()
                }
                PlaybackStatus.PAUSED -> {
                    if (isForegroundActive) {
                        updateNotification()
                    }
                }
                PlaybackStatus.COMPLETED -> {
                    abandonAudioFocus()
                    if (isForegroundActive) {
                        stopForegroundServiceCleanly(removeNotification = false)
                        updateNotification()
                    }
                }
                PlaybackStatus.IDLE -> {
                    abandonAudioFocus()
                    if (isForegroundActive) {
                        stopForegroundServiceCleanly(removeNotification = true)
                    }
                }
            }
        }.launchIn(serviceScope)

        ttsManager.currentIndex.onEach {
            if (isForegroundActive && (
                ttsManager.playbackStatus.value == PlaybackStatus.PLAYING ||
                ttsManager.playbackStatus.value == PlaybackStatus.PAUSED
            )) {
                updateNotification()
            }
        }.launchIn(serviceScope)
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

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
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

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NovelR Book Reader ($displayIndex / $total)")
            .setContentText(currentSentence)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Câu trước", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Tạm dừng" else "Đọc tiếp", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Câu sau", nextIntent)
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
                description = "Shows active sentence and controls for TTS playback."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        abandonAudioFocus()
        mediaSession.release()
        ttsManager.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
