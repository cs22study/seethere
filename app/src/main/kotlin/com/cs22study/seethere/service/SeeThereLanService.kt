package com.cs22study.seethere.service

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cs22study.seethere.MainActivity

/**
 * Keeps the LAN Web Admin Server socket active reliably on Android 7.1 (ColorOS) through Android 15
 * even if the phone screen dims while adding cameras from a desktop browser.
 */
class SeeThereLanService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        createChannel()
        val notification = buildNotification(url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "SeeThere 區網管理後台",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
    }

    private fun buildNotification(url: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("SeeThere 區網管理運行中")
            .setContentText(if (url.isNotBlank()) "瀏覽器開啟：$url" else "僅限同網段瀏覽器連線")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "seethere_lan_server"
        private const val NOTIFICATION_ID = 3042
        private const val EXTRA_URL = "lan_url"

        fun start(context: Context, url: String) {
            val intent = Intent(context, SeeThereLanService::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, SeeThereLanService::class.java))
            }
        }
    }
}
