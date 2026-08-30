package com.example.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class CompanionForegroundService : Service() {

    companion object {
        const val TAG = "CompanionService"
        const val CHANNEL_ID = "companion_service_channel"
        const val NOTIFICATION_ID = 9999
        const val ACTION_STOP = "com.example.companion.STOP"

        fun startService(context: Context) {
            val intent = Intent(context, CompanionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, CompanionForegroundService::class.java))
        }
    }

    private var udpReceiver: UdpReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting UDP listener..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (udpReceiver?.isRunning() != true) {
            startUdpListener()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        udpReceiver?.stop()
        udpReceiver = null
        releaseWakeLock()
    }

    private fun startUdpListener() {
        udpReceiver = UdpReceiver(
            preferredPort = 12345,
            onMessageReceived = { raw ->
                activateScreen(raw)
            },
            onStatusChanged = { status ->
                updateNotification(status)
            }
        )
        udpReceiver?.start()
    }

    private fun activateScreen(rawMessage: String? = null) {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OmniChat:CompanionWake"
            )
            wakeLock?.acquire(10 * 60 * 1000L)

            val intent = Intent(this, CompanionReceiverActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (rawMessage != null) {
                    putExtra("raw_message", rawMessage)
                }
            }
            startActivity(intent)
            updateNotification("Message received!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate screen", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Companion Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps companion running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val openIntent = Intent(this, CompanionReceiverActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, CompanionForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniChat Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "Open", openPending)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(text))
    }
}
