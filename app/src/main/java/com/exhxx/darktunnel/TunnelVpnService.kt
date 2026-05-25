package com.exhxx.darktunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

class TunnelVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
    private var tun2socksProcess: Process? = null

    companion object {
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "ACTION_STOP") {
            stopVpnService("DISCONNECTED")
            return START_NOT_STICKY
        }

        if (action == "ACTION_START") {
            val serverInput = intent.getStringExtra("SERVER") ?: ""
            val uuid = intent.getStringExtra("UUID") ?: ""
            val payloadRaw = intent.getStringExtra("PAYLOAD") ?: ""

            Thread {
                try {
                    // 1. بناء نفق VPN (Global Routing) حسب وصف الذكاء الاصطناعي
                    setupVpnInterface()
                    
                    if (vpnInterface != null) {
                        val tunFd = vpnInterface!!.fd

                        // 2. تشغيل Xray أولاً لفتح المنافذ
                        startXrayEngine(serverInput, uuid, payloadRaw)
                        Thread.sleep(1000)

                        // 3. تشغيل tun2socks (Hev) وتمرير الـ FD السري عبر متغيرات البيئة
                        startTun2Socks(tunFd)

                        isRunning = true
                        showNotification("Connected 🟢 (Global TUN Active)")
                        sendStateBroadcast(true, "CONNECTED")
                    }
                } catch (e: Exception) {
                    stopVpnService("FAILED")
                }
            }.start()
        }
        return START_STICKY
    }

    private fun setupVpnInterface() {
        try {
            val builder = Builder()
            builder.setSession("DarkTunnelPro")
            builder.addAddress("26.26.26.1", 30) // آيبي داخلي جديد للنفق
            builder.addRoute("0.0.0.0", 0)       // التوجيه الشامل لكل شيء
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1500)
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startTun2Socks(fd: Int) {
        try {
            // استخراج المحرك من assets إلى مساحة التطبيق المحمية (filesDir)
            val binaryPath = extractAsset("tun2socks-arm64")

            // كتابة إعدادات Hev ديناميكياً
            val hevConfig = """
            tunnel:
              mtu: 1500
            socks5:
              port: 10808
              address: '127.0.0.1'
              udp: udp
            misc:
              log-level: warn
            """.trimIndent()
            
            val configFile = File(filesDir, "tun2socks.yml")
            configFile.writeText(hevConfig)

            // تشغيل المحرك وتطبيق خدعة TUN_FD الجبارة
            val pb = ProcessBuilder(binaryPath, configFile.absolutePath)
            pb.environment()["TUN_FD"] = fd.toString()
            pb.redirectErrorStream(true)
            tun2socksProcess = pb.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractAsset(name: String): String {
        val outFile = File(filesDir, name)
        if (!outFile.exists()) {
            try {
                assets.open(name).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {}
        }
        // إعطاء صلاحية التشغيل الإجبارية
        outFile.setExecutable(true)
        return outFile.absolutePath
    }

    private fun startXrayEngine(serverInput: String, uuid: String, payloadRaw: String) {
        try {
            // استخدام Xray الموجود مسبقاً في مكتبات الأندرويد
            val xrayPath = applicationInfo.nativeLibraryDir + "/libxray.so"
            File(xrayPath).setExecutable(true)
            val logFile = File(filesDir, "xray_error.log").absolutePath
            
            var server = serverInput
            var port = "80"
            if (serverInput.contains(":")) {
                val parts = serverInput.split(":")
                server = parts[0]
                port = parts[1]
            }

            var method = "GET"
            var path = "/"
            var headersJson = """"Host": ["$server"], "Connection": ["keep-alive"]"""

            val raw = payloadRaw.replace("[host_port]", "$server:$port")
            if (raw.isNotBlank()) {
                val lines = raw.split("[crlf]", "\n")
                val firstLine = lines[0].trim()
                
                val cleanFirstLine = firstLine.replace(Regex("HTTP/1\\.[0-9].*"), "").trim()
                val firstSpace = cleanFirstLine.indexOf(" ")
                if (firstSpace != -1) {
                    method = cleanFirstLine.substring(0, firstSpace).trim()
                    path = cleanFirstLine.substring(firstSpace + 1).trim()
                    if (path.isEmpty()) path = "/"
                } else {
                    method = cleanFirstLine
                }

                val customHeaders = mutableListOf<String>()
                for (i in 1 until lines.size) {
                    val line = lines[i].trim()
                    if (line.contains(":")) {
                        val key = line.substringBefore(":").trim()
                        val value = line.substringAfter(":").trim().replace("\"", "\\\"")
                        customHeaders.add("\"$key\": [\"$value\"]")
                    }
                }
                if (customHeaders.isNotEmpty()) {
                    headersJson = customHeaders.joinToString(", ")
                }
            }

            val config = """
            {
              "log": { "loglevel": "warning", "error": "$logFile" },
              "dns": { "servers": [ "1.1.1.1", "8.8.8.8" ] },
              "inbounds": [
                {
                  "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
                  "settings": { "auth": "noauth", "udp": true }
                }
              ],
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": {
                    "vnext": [ { "address": "$server", "port": ${port.toIntOrNull() ?: 80}, "users": [ { "id": "$uuid", "encryption": "none", "level": 0 } ] } ]
                  },
                  "streamSettings": {
                    "network": "tcp", "security": "none",
                    "tcpSettings": {
                      "header": { "type": "http", "request": { "version": "1.1", "method": "$method", "path": ["$path"], "headers": { $headersJson } } }
                    }
                  },
                  "mux": { "enabled": true, "concurrency": 8 }
                },
                { "protocol": "freedom", "settings": {} }
              ]
            }
            """.trimIndent()
            
            val configFile = File(filesDir, "config.json")
            configFile.writeText(config)

            xrayProcess = ProcessBuilder(xrayPath, "-c", configFile.absolutePath).start()
            
        } catch (e: Exception) {}
    }

    private fun stopVpnService(msg: String) {
        isRunning = false
        sendStateBroadcast(false, msg)
        try { tun2socksProcess?.destroy(); tun2socksProcess = null } catch (e: Exception) {}
        try { xrayProcess?.destroy(); xrayProcess = null } catch (e: Exception) {}
        try { vpnInterface?.close(); vpnInterface = null } catch (e: Exception) {}
        stopForeground(true)
        stopSelf()
    }

    private fun showNotification(msg: String) {
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "DARK_TUNNEL_CH").setContentTitle("@exhxx78 Pro").setContentText(msg).setSmallIcon(android.R.drawable.ic_dialog_info).build()
        } else {
            Notification.Builder(this).setContentTitle("@exhxx78 Pro").setContentText(msg).setSmallIcon(android.R.drawable.ic_dialog_info).build()
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
        startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("DARK_TUNNEL_CH", "VPN Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendStateBroadcast(running: Boolean, msg: String = "") {
        val intent = Intent("COM.EXHXX.DARKTUNNEL.UPDATE_STATUS").apply { putExtra("RUNNING", running); putExtra("MSG", msg) }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopVpnService("DISCONNECTED")
        super.onDestroy()
    }
}
