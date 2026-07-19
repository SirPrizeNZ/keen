package com.keenzero.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.keenzero.app.KeenActivity
import com.keenzero.app.R

/**
 * Playback-scoped foreground priority only. KeenActivity and its single WebView
 * remain the sole owners of UI, navigation and media control.
 */
class PlaybackPriorityService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        Log.i(TAG, "Playback foreground priority active")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(): Notification {
        val launch = Intent(this, KeenActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Media playback is active")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "KeenPlaybackService"
        private const val CHANNEL_ID = "keen_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.keenzero.app.action.PLAYBACK_PRIORITY_START"

        fun setPlaybackActive(context: Context, active: Boolean) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, PlaybackPriorityService::class.java)
                .setAction(ACTION_START)
            if (active) {
                try {
                    ContextCompat.startForegroundService(appContext, intent)
                } catch (t: Throwable) {
                    Log.e(TAG, "Unable to start playback foreground priority", t)
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }
}
