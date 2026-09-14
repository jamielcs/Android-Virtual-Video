package id.armagic.virtualvideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class BackgroundVideoService : Service() {

    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val uriString = intent.getStringExtra(EXTRA_VIDEO_URI)
                if (uriString.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                val loop = intent.getBooleanExtra(EXTRA_LOOP, true)
                val mute = intent.getBooleanExtra(EXTRA_MUTE, false)

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Video engine running")
                )

                startEngine(
                    uri = Uri.parse(uriString),
                    loop = loop,
                    mute = mute
                )
            }
        }

        return START_STICKY
    }

    private fun startEngine(uri: Uri, loop: Boolean, mute: Boolean) {
        player?.release()

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode =
                if (loop) Player.REPEAT_MODE_ONE
                else Player.REPEAT_MODE_OFF

            volume = if (mute) 0f else 1f

            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    private fun stopEngine() {
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Vir Vid 5")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Video Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the selected video engine running in background."
        }

        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "id.armagic.virvid5.action.START_ENGINE"
        const val ACTION_STOP = "id.armagic.virvid5.action.STOP_ENGINE"

        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_MUTE = "mute"

        private const val CHANNEL_ID = "vir_vid_5_background_engine"
        private const val NOTIFICATION_ID = 5005
    }
}
