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
    private var isDisconnectIntended = false
    
    private var serverStr = ""
    private var uuidStr = ""
    private var payloadStr = ""

    companion object {
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "ACTION_STOP") {
            isDisconnectIntended = true
            stopVpnService("DISCONNECTED")
            return START_NOT_STICKY
        }

        if (action == "ACTION_START") {
            serverStr = intent.getStringExtra("SERVER") ?: ""
            uuidStr = intent.getStringExtra("UUID") ?: ""
            payloadStr = intent.getStringExtra("PAYLOAD") ?: ""

            if (serverStr.trim().isEmpty()) {
                stopVpnService("FAILED")
                return START_NOT_STICKY
            }

            isDisconnectIntended = false
            startVpnThread()
        }

        return START_STICKY
    }

    private fun startVpnThread() {
        Thread {
            while (!isDisconnectIntended) {
                try {
                    cleanupOldConnection()
                    
                    startXrayEngine(serverStr, uuidStr, payloadStr)
                    Thread.sleep(1500) 
                    
                    setupVpnInterface()
                    
                    if (vpnInterface != null) {
                        val tunFd = vpnInterface!!.fd
                        startTun2Socks(tunFd)
                        
                        isRunning = true
                        showNotification("Connected 🟢 (Tun2Socks Engine Online)")
                        sendStateBroadcast(true, "CONNECTED")

                        // الحارس الذكي يراقب المحركات
                        while (!isDisconnectIntended) {
                            Thread.sleep(3000)
                            if (isProcessDead(xrayProcess) || isProcessDead(tun2socksProcess)) {
                                break 
                            }
                        }
                    }

                    if (!isDisconnectIntended) {
                        showNotification("Connection dropped! Reconnecting...")
                        sendStateBroadcast(true, "RECONNECTING")
                        Thread.sleep(2000)
                    }
                    
                } catch (e: Exception) {
                    if (!isDisconnectIntended) {
                        showNotification("Connection dropped! Reconnecting...")
                        sendStateBroadcast(true, "RECONNECTING")
                        try { Thread.sleep(2000) } catch (ignored: Exception) {}
                    }
                }
            }
        }.start()
    }

    private fun isProcessDead(p: Process?): Boolean {
        if (p == null) return true
        return try {
            p.exitValue() 
            true 
        } catch (e: IllegalThreadStateException) {
            false 
        }
    }

    private fun cleanupOldConnection() {
        try { tun2socksProcess?.destroy(); tun2socksProcess = null } catch (e: Exception) {}
        try { xrayProcess?.destroy(); xrayProcess = null } catch (e: Exception) {}
        try { vpnInterface?.close(); vpnInterface = null } catch (e: Exception) {}
    }

    private fun setupVpnInterface() {
        val builder = Builder()
        builder.setSession("@exhxx78")
        builder.setMtu(1400)
        builder.addAddress("10.0.0.2", 24)
        
        // مسار التوجيه الإجباري لسحب كل بيانات التطبيقات
        builder.addRoute("0.0.0.0", 0) 
        
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        
        // استثناء التطبيق نفسه لمنع التكرار اللانهائي (Loop)
        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
        
        vpnInterface = builder.establish()
    }

    private fun startTun2Socks(fd: Int) {
        try {
            val tun2socksPath = applicationInfo.nativeLibraryDir + "/libtun2socks.so"
            
            // 🔥 تصحيح قاتل: إعطاء صلاحية التشغيل الإجبارية للمحرك 🔥
            File(tun2socksPath).setExecutable(true)

            // 🔥 تصحيح قاتل 2: تغيير الصيغة لتطابق محرك DarkTunnel (badvpn) 🔥
            val command = arrayOf(
                tun2socksPath,
                "--tundev", "fd:$fd", 
                "--netif-ipaddr", "10.0.0.2",
                "--netif-netmask", "255.255.255.0",
                "--socks-server-addr", "127.0.0.1:10808",
                "--loglevel", "none"
            )
            tun2socksProcess = ProcessBuilder(*command).start()
        } catch (e: Exception) {}
    }

    private fun startXrayEngine(serverInput: String, uuid: String, payloadRaw: String) {
        val xrayPath = applicationInfo.nativeLibraryDir + "/libxray.so"
        
        // إعطاء صلاحية التشغيل لمحرك Xray أيضاً لضمان عدم توقفه
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
              "port": 10809, "listen": "127.0.0.1", "protocol": "http",
              "settings": { "allowTransparent": false },
              "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
            },
            {
              "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
              "settings": { "auth": "noauth", "udp": true },
              "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
            }
          ],
          "outbounds": [
            {
              "tag": "proxy", "protocol": "vless",
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
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              { "type": "field", "port": 53, "outboundTag": "proxy" },
              { "type": "field", "network": "tcp,udp", "outboundTag": "proxy" }
            ]
          },
          "policy": {
            "levels": { "0": { "connIdle": 300, "handshake": 4, "uplinkOnly": 1, "downlinkOnly": 1 } },
            "system": { "statsOutboundUplink": false, "statsOutboundDownlink": false }
          }
        }
        """.trimIndent()
        
        val configFile = File(filesDir, "config.json")
        configFile.writeText(config)

        val command = arrayOf(xrayPath, "-c", configFile.absolutePath)
        xrayProcess = ProcessBuilder(*command).start()
    }

    private fun stopVpnService(msg: String) {
        isRunning = false
        sendStateBroadcast(false, msg)
        cleanupOldConnection()
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
        isDisconnectIntended = true
        stopVpnService("DISCONNECTED")
        super.onDestroy()
    }
}
