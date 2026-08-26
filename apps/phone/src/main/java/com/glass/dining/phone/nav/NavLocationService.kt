package com.glass.dining.phone.nav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class NavLocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("到店导航进行中")
            .setContentText("正在用定位更新步行指引")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
        PhoneGps.start(this)
    }

    override fun onDestroy() {
        PhoneGps.stop()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "到店导航", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL = "dining_nav"
        private const val NOTIFY_ID = 41

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavLocationService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavLocationService::class.java))
        }
    }
}
