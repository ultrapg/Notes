package com.xenonware.notes.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xenonware.notes.MainActivity
import com.xenonware.notes.R
import com.xenonware.notes.util.audio.SpeechDownloadState
import com.xenonware.notes.util.audio.SpeechModelDownloadManager
import com.xenonware.notes.util.audio.SpeechModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service keeping the `Voz` speech model download alive in the background with a
 * partial WakeLock and real-time progress / speed / ETA notification.
 */
class SpeechModelDownloadService : Service() {

    companion object {
        private const val TAG = "SpeechDlService"
        private const val CHANNEL_ID = "speech_model_downloads"
        private const val NOTIFICATION_ID = 8922

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SpeechModelDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start foreground download service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, SpeechModelDownloadService::class.java))
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()

        val initial = SpeechModelDownloadManager.state.value
        postForeground(buildInitialOrProgressNotification(initial))

        serviceScope.launch {
            SpeechModelDownloadManager.state.collect { state ->
                when (state) {
                    is SpeechDownloadState.Downloading -> {
                        postForeground(buildProgressNotification(state))
                    }
                    is SpeechDownloadState.Completed -> {
                        showTerminalNotification(buildCompletedNotification())
                        stopForegroundCompat()
                        stopSelf()
                    }
                    is SpeechDownloadState.Failed -> {
                        showTerminalNotification(buildFailedNotification(state.message))
                        stopForegroundCompat()
                        stopSelf()
                    }
                    is SpeechDownloadState.Paused, SpeechDownloadState.Idle -> {
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        postForeground(buildInitialOrProgressNotification(SpeechModelDownloadManager.state.value))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildInitialOrProgressNotification(state: SpeechDownloadState): Notification {
        return if (state is SpeechDownloadState.Downloading) {
            buildProgressNotification(state)
        } else {
            buildProgressNotification(
                SpeechDownloadState.Downloading(
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = SpeechModelSpec.TOTAL_BYTES,
                    statusNote = "Starting download...",
                )
            )
        }
    }

    private fun postForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not post foreground notification: ${e.message}")
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun showTerminalNotification(notification: Notification) {
        val canPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        } else {
            true
        }
        if (!canPost) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.w(TAG, "Could not post terminal notification", e)
        }
    }

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildProgressNotification(state: SpeechDownloadState.Downloading): Notification {
        val percent = (state.clampedProgress * 100f).roundToInt()
        val subtitle = state.statusNote ?: "${state.formattedSpeed} • ${state.formattedEta}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_whisper_download)
            .setContentTitle("Downloading ${SpeechModelSpec.DISPLAY_NAME} ($percent%) — ${state.formattedBytes}")
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(1000, (state.clampedProgress * 1000f).roundToInt().coerceIn(0, 1000), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .build()
    }

    private fun buildCompletedNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_whisper_download)
            .setContentTitle("${SpeechModelSpec.FULL_NAME} ready")
            .setContentText("Offline multilingual transcription (25 languages) is now ready.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun buildFailedNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_whisper_download)
            .setContentTitle("${SpeechModelSpec.DISPLAY_NAME} download paused")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Speech Model Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress while downloading on-device speech recognition models"
                    setShowBadge(false)
                    enableVibration(false)
                }
            )
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Notes::SpeechModelDownloadWakeLock",
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {
        }
    }
}
