package com.example.srtsender

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service for continuous video/GPS streaming
 * Keeps running even when app is in background or screen is off
 */
class StreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "StreamingServiceChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.srtsender.START_STREAMING"
        const val ACTION_STOP = "com.example.srtsender.STOP_STREAMING"
        private const val TAG = "StreamingService"
        
        var isRunning = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent)
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming(intent: Intent) {
        val boatId = intent.getStringExtra("boatId") ?: "unknown"
        
        // Acquire wake lock to keep CPU active
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SrtSender::StreamingWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }

        // Start foreground with notification
        val notification = createNotification(boatId)
        startForeground(NOTIFICATION_ID, notification)
        
        isRunning = true
        Log.d(TAG, "Streaming service started for $boatId")
    }

    private fun stopStreaming() {
        isRunning = false
        
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Streaming service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "กำลัง Stream ภาพและ GPS"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(boatId: String): Notification {
        // Intent to open MainActivity when notification is tapped
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop streaming
        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 กำลัง Stream")
            .setContentText("$boatId - แตะเพื่อเปิดแอป")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingOpenIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "หยุด",
                pendingStopIntent
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        Log.d(TAG, "Service destroyed")
    }
}
