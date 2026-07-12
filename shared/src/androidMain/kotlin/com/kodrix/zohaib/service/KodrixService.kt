package com.kodrix.zohaib.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.util.Log

class KodrixService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "kodrix_background_service"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("KodrixService", "Service starting...")
        
        // Start as foreground service to prevent system freezing
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Acquire WakeLock to keep CPU running when screen is off
        acquireWakeLock()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("KodrixService", "Service destroying...")
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Background Tasks"
            val descriptionText = "Keeps the IDE alive during long operations like npm install or git clone."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kodrix Background Worker")
            .setContentText("Keeping terminal and LSP processes alive...")
            .setSmallIcon(android.R.drawable.stat_notify_sync) // Placeholder icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Kodrix:BackgroundTaskWakeLock"
            )
        }
        
        if (wakeLock?.isHeld == false) {
            Log.d("KodrixService", "Acquiring WakeLock")
            wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes timeout as safety */)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            Log.d("KodrixService", "Releasing WakeLock")
            wakeLock?.release()
        }
        wakeLock = null
    }
}
