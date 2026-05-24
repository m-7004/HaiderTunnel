package com.haider.tunnel.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.haider.tunnel.proxy.ProxyManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class TunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyManager: ProxyManager? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val server = intent?.getStringExtra("server") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 80)
        val uuid = intent.getStringExtra("uuid") ?: return START_NOT_STICKY
        val payload = intent.getStringExtra("payload") ?: ""

        Thread { startTunnel(server, port, uuid, payload) }.start()
        return START_STICKY
    }

    private fun startTunnel(server: String, port: Int, uuid: String, payload: String) {
        try {
            // تشغيل proxy
            proxyManager = ProxyManager(server, port, uuid, payload)
            proxyManager?.start()

            Thread.sleep(500)

            // إعداد VPN
            val builder = Builder()
            builder.setMtu(1500)
            builder.addAddress("10.0.0.2", 24)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.addRoute("0.0.0.0", 0)
            builder.setSession("Haider Tunnel")
            builder.protect(proxyManager!!.localPort)

            vpnInterface = builder.establish()
            running = true

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        running = false
        proxyManager?.stop()
        vpnInterface?.close()
        super.onDestroy()
    }
}
