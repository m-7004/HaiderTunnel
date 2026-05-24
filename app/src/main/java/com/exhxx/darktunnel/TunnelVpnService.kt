package com.exhxx.darktunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

class TunnelVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val server = intent?.getStringExtra("SERVER") ?: "Unknown"

        // درع الحماية الأول: منع انهيار الإشعارات
        try {
            createNotificationChannel()
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, "DARK_TUNNEL_CH")
                    .setContentTitle("DarkTunnel Pro")
                    .setContentText("Connected to Server: $server")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            } else {
                Notification.Builder(this)
                    .setContentTitle("DarkTunnel Pro")
                    .setContentText("Connected to Server: $server")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            }
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // درع الحماية الثاني: إنشاء واجهة وهمية للـ VPN لكي لا يعتبره النظام خدمة ميتة ويقتلها
        try {
            val builder = Builder()
            builder.setSession("DarkTunnel")
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            builder.establish() 
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("DARK_TUNNEL_CH", "VPN Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
