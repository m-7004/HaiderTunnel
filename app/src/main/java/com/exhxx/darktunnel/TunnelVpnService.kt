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
    private var protectServer: ProtectServer? = null

    companion object {
        var isRunning = false
    }

    private val protectSocketPath by lazy {
        "${filesDir.absolutePath}/protect.sock"
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
                    // 1. تشغيل سيرفر الحماية لـ Xray
                    protectServer = ProtectServer(this, protectSocketPath).also { it.start() }
                    Thread.sleep(200)

                    var serverIp = serverInput
                    if (serverInput.contains(":")) {
                        serverIp = serverInput.split(":")[0]
                    }

                    // 2. تشغيل Xray
                    startXrayEngine(serverInput, serverIp, uuid, payloadRaw)
                    Thread.sleep(1500)

                    // 3. بناء النفق
                    setupVpnInterface()
                    
                    if (vpnInterface != null) {
                        val tunFd = vpnInterface!!.detachFd()

                        // 4. تشغيل محرك الدارك الأصلي (tun2socks) بدلاً من Hev المقتول
                        startTun2Socks(tunFd)

                        isRunning = true
                        showNotification("Connected 🟢 (Global VPN)")
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
            builder.setSession("@exhxx78_Pro")
            builder.addAddress("26.26.26.1", 30)
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1500)
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            vpnInterface = builder.establish()
        } catch (e: Exception) {}
    }

    private fun startTun2Socks(fd: Int) {
        try {
            // استخدام المحرك الأصلي المجاز من الأندرويد
            val tun2socksPath = applicationInfo.nativeLibraryDir + "/libtun2socks.so"
            File(tun2socksPath).setExecutable(true)

            val command = arrayOf(
                tun2socksPath,
                "--tundev", "fd:$fd",
                "--netif-ipaddr", "26.26.26.2",
                "--netif-netmask", "255.255.255.0",
                "--socks-server-addr", "127.0.0.1:10808",
                "--loglevel", "none"
            )
            
            val pb = ProcessBuilder(*command)
            pb.environment()["ANDROID_DATA"] = filesDir.absolutePath 
            tun2socksProcess = pb.start()
        } catch (e: Exception) {}
    }

    private fun startXrayEngine(serverInput: String, serverIp: String, uuid: String, payloadRaw: String) {
        try {
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
                },
                {
                  "port": 10809, "listen": "127.0.0.1", "protocol": "http"
                }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
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
                { "tag": "direct", "protocol": "freedom", "settings": {} }
              ],
              "routing": {
                "rules": [
                  { "type": "field", "outboundTag": "direct", "ip": ["$serverIp/32"] }
                ]
              }
            }
            """.trimIndent()
            
            val configFile = File(filesDir, "config.json")
            configFile.writeText(config)

            val pb = ProcessBuilder(xrayPath, "-c", configFile.absolutePath)
            pb.environment()["XRAY_PROTECT_SOCKET"] = protectSocketPath
            pb.environment()["ANDROID_DATA"] = filesDir.absolutePath
            pb.redirectErrorStream(true)
            xrayProcess = pb.start()
            
        } catch (e: Exception) {}
    }

    private fun stopVpnService(msg: String) {
        isRunning = false
        sendStateBroadcast(false, msg)
        protectServer?.stop()
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
