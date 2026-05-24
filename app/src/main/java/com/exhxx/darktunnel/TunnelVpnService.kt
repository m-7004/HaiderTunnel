package com.exhxx.darktunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.VpnService
import android.os.Build

class TunnelVpnService : VpnService() {
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "DARK_TUNNEL_CH")
                .setContentTitle("DarkTunnel Pro")
                .setContentText("Connected and running stably")
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("DarkTunnel Pro")
                .setContentText("Connected and running stably")
                .build()
        }
        startForeground(1, notification)
        
        // هنا سيتم تفعيل الاتصال بالنواة (tun2socks)
        
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
