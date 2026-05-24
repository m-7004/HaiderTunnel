package com.haider.tunnel.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.haider.tunnel.proxy.ProxyManager
import java.io.File

class TunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyManager: ProxyManager? = null
    private var xrayProcess: Process? = null

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
            // نسخ xray لمجلد قابل للتنفيذ
            val xrayFile = File(filesDir, "xray")
            if (!xrayFile.exists()) {
                assets.open("xray").use { input ->
                    xrayFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                xrayFile.setExecutable(true)
            }

            // كتابة config.json
            val config = """
{
  "log": {"loglevel": "none"},
  "inbounds": [
    {
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "socks",
      "settings": {"auth": "noauth", "udp": true}
    }
  ],
  "outbounds": [
    {
      "protocol": "vless",
      "settings": {
        "vnext": [{
          "address": "$server",
          "port": $port,
          "users": [{
            "id": "$uuid",
            "encryption": "none"
          }]
        }]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "none"
      }
    }
  ]
}
""".trimIndent()

            val configFile = File(filesDir, "config.json")
            configFile.writeText(config)

            // تشغيل proxy للـ payload
            proxyManager = ProxyManager(server, port, uuid, payload)
            proxyManager?.start()

            // تشغيل xray
            xrayProcess = ProcessBuilder(xrayFile.absolutePath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            // إعداد VPN
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
        xrayProcess?.destroy()
        vpnInterface?.close()
        super.onDestroy()
    }
}
