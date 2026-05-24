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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val server = intent?.getStringExtra("server") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 80)
        val uuid = intent.getStringExtra("uuid") ?: return START_NOT_STICKY
        val payload = intent.getStringExtra("payload") ?: ""

        startForegroundNotification()
        Thread { startTunnel(server, port, uuid, payload) }.start()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "haider_tunnel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Haider Tunnel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Haider Tunnel")
            .setContentText("متصل ✓")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun startTunnel(server: String, port: Int, uuid: String, payload: String) {
        try {
            proxyManager = ProxyManager(server, port, uuid, payload)
            proxyManager?.start()
            Thread.sleep(500)

            val builder = Builder()
            builder.setMtu(1500)
            builder.addAddress("10.0.0.2", 24)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.addRoute("0.0.0.0", 0)
            builder.setSession("Haider Tunnel")
            vpnInterface = builder.establish()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        proxyManager?.stop()
        vpnInterface?.close()
        stopForeground(true)
        super.onDestroy()
    }
}
