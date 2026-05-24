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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class TunnelVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null

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

            if (serverInput.trim().isEmpty()) {
                stopVpnService("FAILED")
                return START_NOT_STICKY
            }

            Thread {
                try {
                    startXrayEngine(serverInput, uuid, payloadRaw)
                    setupVpnInterface()
                    
                    isRunning = true
                    showNotification("Connected 🟢")
                    sendStateBroadcast(true, "CONNECTED")
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
            builder.setSession("@exhxx78")
            builder.setMtu(1500)
            builder.addAddress("10.0.0.2", 24)
            builder.addDnsServer("8.8.8.8")
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1", 10809))
            }
            vpnInterface = builder.establish()
        } catch (e: Exception) {}
    }

    private fun startXrayEngine(serverInput: String, uuid: String, payloadRaw: String) {
        try {
            val xrayPath = applicationInfo.nativeLibraryDir + "/libxray.so"
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
                
                // هنا الذكاء: نأخذ مفتاحك الكامل ونوزعه بذكاء حتى Xray يطبعه قطعة واحدة!
                val firstSpace = firstLine.indexOf(" ")
                if (firstSpace != -1) {
                    method = firstLine.substring(0, firstSpace).trim() // مثلا: HTTP/78
                    
                    // نأخذ باقي السطر (مثلا: 2026 300 ok) ونحذف منه HTTP/1.1 حتى لا تتكرر
                    var remaining = firstLine.substring(firstSpace + 1)
                    remaining = remaining.replace(Regex("HTTP/1\\.[0-9]", RegexOption.IGNORE_CASE), "").trim()
                    
                    if (remaining.isNotBlank()) {
                        path = remaining
                    }
                } else {
                    method = firstLine
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
              "inbounds": [
                { "port": 10809, "listen": "0.0.0.0", "protocol": "http", "settings": { "allowTransparent": false } }
              ],
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": {
                    "vnext": [ { "address": "$server", "port": ${port.toIntOrNull() ?: 80}, "users": [ { "id": "$uuid", "encryption": "none" } ] } ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "security": "none",
                    "tcpSettings": {
                      "header": { 
                        "type": "http", 
                        "request": { 
                          "version": "1.1", 
                          "method": "$method", 
                          "path": ["$path"], 
                          "headers": { $headersJson } 
                        } 
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent()
            
            val configFile = File(filesDir, "config.json")
            configFile.writeText(config)

            val command = arrayOf(xrayPath, "-c", configFile.absolutePath)
            xrayProcess = ProcessBuilder(*command).start()
            
        } catch (e: Exception) {}
    }

    private fun stopVpnService(msg: String) {
        isRunning = false
        sendStateBroadcast(false, msg)
        try { xrayProcess?.destroy(); xrayProcess = null } catch (e: Exception) {}
        try { vpnInterface?.close(); vpnInterface = null } catch (e: Exception) {}
        stopForeground(true)
        stopSelf()
    }

    private fun showNotification(msg: String) {
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "DARK_TUNNEL_CH")
                .setContentTitle("@exhxx78 Pro")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("@exhxx78 Pro")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
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
        val intent = Intent("COM.EXHXX.DARKTUNNEL.UPDATE_STATUS").apply { 
            putExtra("RUNNING", running)
            putExtra("MSG", msg)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopVpnService("DISCONNECTED")
        super.onDestroy()
    }
}
