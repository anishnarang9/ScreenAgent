package com.example.screenagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.screenagent.HomeActivity

class MediaProjectionService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "media_projection_service"
    }
    
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    
    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            
            val resultCode = intent?.getIntExtra("result_code", -1) ?: -1
            val resultData = intent?.getParcelableExtra<Intent>("result_data")
            
            Log.d("MediaProjectionService", "Starting with resultCode: $resultCode, resultData: $resultData")
            
            if (resultCode != -1 && resultData != null) {
                try {
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                    MediaProjectionHolder.mediaProjection = mediaProjection
                    Log.d("MediaProjectionService", "MediaProjection created successfully")
                } catch (e: Exception) {
                    Log.e("MediaProjectionService", "Failed to create MediaProjection", e)
                }
            } else {
                Log.w("MediaProjectionService", "Invalid result data")
            }
        } catch (e: Exception) {
            Log.e("MediaProjectionService", "Error in onStartCommand", e)
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Projection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Holds MediaProjection for screenshot capture"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, HomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenAgent Screenshot Service")
            .setContentText("Holding screenshot permission")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        MediaProjectionHolder.mediaProjection = null
    }
}
