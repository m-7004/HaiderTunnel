package com.exhxx.darktunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File

class TunnelVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
    private var hevProcess: Process? = null

    companion object {
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            stopVpnService("DISCONNECTED")
            return START_NOT_STICKY
        }

        val serverInput = intent?.getStringExtra("SERVER") ?: ""
        val uuid = intent?.getStringExtra("UUID") ?: ""
        val payloadRaw = intent?.getStringExtra("PAYLOAD") ?: ""

        Thread {
            try {
                // 1. تشغيل Xray (الذي سيتصل بزين مباشرة بفضل استثناء التطبيق)
                startXrayEngine(serverInput, uuid, payloadRaw)
                Thread.sleep(1000)

                // 2. بناء النفق واستثناء التطبيق لمنع الدوران بحلقة مفرغة
                val builder = Builder()
                builder.setSession("@exhxx78_Pro")
                builder.addAddress("26.26.26.1", 30)
                builder.addRoute("0.0.0.0", 0)
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")
                builder.setMtu(1500)
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                
                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    val tunFdInt = vpnInterface!!.fd
                    
                    // 🔥 الضربة القاضية: كسر قفل الأندرويد عن مفتاح الـ FD ليتمكن المحرك من استلامه 🔥
                    try {
                        Os.fcntlInt(vpnInterface!!.fileDescriptor, OsConstants.F_SETFD, 0)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 3. تشغيل محرك سحب البيانات
                    startHevEngine(tunFdInt)

                    isRunning = true
                    showNotification("Connected 🟢 (Full Tunnel Active)")
                    sendStateBroadcast(true, "CONNECTED")
                }
            } catch (e: Exception) {
                stopVpnService("FAILED")
            }
        }.start()

        return START_STICKY
    }

    private fun startHevEngine(tunFd: Int) {
        try {
            // تشغيل المحرك من مسار المكتبات الرسمي لتخطي حظر التشغيل
            val hevPath = applicationInfo.nativeLibraryDir + "/libhev.so"
            File(hevPath).setExecutable(true)

            val hevConfig = """
            tunnel:
              mtu: 1500
            socks5:
              port: 10808
              address: '127.0.0.1'
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent()
            
            val configFile = File(filesDir, "tun2socks.yml")
            configFile.writeText(hevConfig)

            val pb = ProcessBuilder(hevPath, configFile.absolutePath)
            pb.environment()["TUN_FD"] = tunFd.toString()
            pb.redirectErrorStream(true)
            hevProcess = pb.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startXrayEngine(serverInput: String, uuid: String, payloadRaw: String) {
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
                val cleanFirstLine = lines[0].trim().replace(Regex("HTTP/1\\.[0-9].*"), "").trim()
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
                if (customHeaders.isNotEmpty()) headersJson = customHeaders.joinToString(", ")
            }

            val config = """
            {
              "log": { "loglevel": "warning", "error": "$logFile" },
              "dns": { "servers": [ "1.1.1.1", "8.8.8.8" ] },
              "inbounds": [
                { "port": 10808, "listen": "127.0.0.1", "protocol": "socks", "settings": { "auth": "noauth", "udp": true } },
                { "port": 10809, "listen": "127.0.0.1", "protocol": "http" }
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

            val pb = ProcessBuilder(xrayPath, "-c", configFile.absolutePath)
            pb.environment()["ANDROID_DATA"] = filesDir.absolutePath
            pb.redirectErrorStream(true)
            xrayProcess = pb.start()
            
        } catch (e: Exception) {}
    }

    private fun stopVpnService(msg: String) {
        isRunning = false
        sendStateBroadcast(false, msg)
        try { hevProcess?.destroy(); hevProcess = null } catch (e: Exception) {}
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
