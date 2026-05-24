package com.haider.tunnel.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.haider.tunnel.proxy.ProxyManager
import java.net.InetAddress

class TunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyManager: ProxyManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val server = intent?.getStringExtra("server") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 80)
        val uuid = intent.getStringExtra("uuid") ?: return START_NOT_STICKY
        val payload = intent.getStringExtra("payload") ?: ""

        Thread {
            startTunnel(server, port, uuid, payload)
        }.start()

        return START_STICKY
    }

    private fun startTunnel(server: String, port: Int, uuid: String, payload: String) {
        try {
            proxyManager = ProxyManager(server, port, uuid, payload)
            proxyManager?.start()

            val builder = Builder()
            builder.setMtu(1500)
            builder.addAddress("10.0.0.2", 24)
            builder.addDnsServer("8.8.8.8")
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
        super.onDestroy()
    }
}
