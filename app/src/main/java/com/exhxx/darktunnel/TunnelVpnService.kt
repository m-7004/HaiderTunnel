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

    companion object {
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "ACTION_STOP") {
            stopVpnService()
            return START_NOT_STICKY
        }

        if (action == "ACTION_START") {
            val server = intent.getStringExtra("SERVER") ?: "Unknown"
            val port = intent.getStringExtra("PORT") ?: "80"
            val uuid = intent.getStringExtra("UUID") ?: ""
            val payload = intent.getStringExtra("PAYLOAD") ?: ""
            
            isRunning = true
            sendStateBroadcast(true)
            showNotification(server, "Connecting...")

            Thread {
                try {
                    startXrayEngine(server, port, uuid, payload)
                    setupVpnInterface()
                    showNotification(server, "Connected 🟢")
                } catch (e: Exception) {
                    e.printStackTrace()
                    showNotification(server, "Error 🔴")
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

    private fun startXrayEngine(server: String, port: String, uuid: String, payload: String) {
        try {
            val xrayPath = applicationInfo.nativeLibraryDir + "/libxray.so"
            val logFile = File(filesDir, "xray_error.log").absolutePath

            // تم جعل حقل الـ User-Agent يستقبل قيمة حقل الـ Payload الأمامي بالكامل وبدون أي وسيط مدمج
            val config = """
            {
              "log": { 
                "loglevel": "warning",
                "error": "$logFile"
              },
              "inbounds": [
                {
                  "port": 10809,
                  "listen": "0.0.0.0",
                  "protocol": "http",
                  "settings": { "allowTransparent": false }
                }
              ],
              "outbounds": [
                {
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "$server",
                        "port": ${port.toIntOrNull() ?: 80},
                        "users": [ { "id": "$uuid", "encryption": "none" } ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "security": "none",
                    "tcpSettings": {
                      "header": {
                        "type": "http",
                        "request": {
                          "version": "1.1",
                          "method": "GET",
                          "path": ["/"],
                          "headers": {
                            "Host": ["proxy.exhxx.com"],
                            "User-Agent": ["$payload"],
                            "Connection": ["keep-alive"]
                          }
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

    private fun stopVpnService() {
        isRunning = false
        sendStateBroadcast(false)
        try { xrayProcess?.destroy(); xrayProcess = null } catch (e: Exception) {}
        try { vpnInterface?.close(); vpnInterface = null } catch (e: Exception) {}
        stopForeground(true)
        stopSelf()
    }

    private fun showNotification(serverIp: String, status: String) {
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "DARK_TUNNEL_CH")
                .setContentTitle("@exhxx78 Pro")
                .setContentText("$serverIp - $status")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("@exhxx78 Pro")
                .setContentText("$serverIp - $status")
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

    private fun sendStateBroadcast(running: Boolean) {
        val intent = Intent("COM.EXHXX.DARKTUNNEL.UPDATE_STATUS").apply { putExtra("RUNNING", running) }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopVpnService()
        super.onDestroy()
    }
}
