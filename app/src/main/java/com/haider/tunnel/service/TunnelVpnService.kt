package com.haider.tunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.haider.tunnel.proxy.ProxyManager

class TunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyManager: ProxyManager? = null
    private var running = false

    companion object {
        const val ACTION_STOP = "STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val server = intent?.getStringExtra("server") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 80)
        val uuid = intent.getStringExtra("uuid") ?: return START_NOT_STICKY
        val payload = intent.getStringExtra("payload") ?: ""

        startForegroundNotification()

        Thread {
            startTunnel(server, port, uuid, payload)
        }.start()

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "haider_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Haider Tunnel VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Haider Tunnel")
            .setContentText("جاري الاتصال...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun startTunnel(server: String, port: Int, uuid: String, payload: String) {
        try {
            // تشغيل proxy
            proxyManager = ProxyManager(server, port, uuid, payload)
            proxyManager?.start()
            Thread.sleep(1000)

            // إعداد VPN interface
            val builder = Builder()
            builder.setMtu(1500)
            builder.addAddress("10.0.0.2", 24)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.addRoute("0.0.0.0", 0)
            builder.setSession("Haider Tunnel")
            builder.setBlocking(true)

            vpnInterface = builder.establish()
            running = true

            // هذا الـ loop يخلي الـ service شغالة
            while (running && vpnInterface != null) {
                Thread.sleep(1000)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        running = false
        proxyManager?.stop()
        try { vpnInterface?.close() } catch (e: Exception) {}
        stopForeground(true)
        super.onDestroy()
    }
}
