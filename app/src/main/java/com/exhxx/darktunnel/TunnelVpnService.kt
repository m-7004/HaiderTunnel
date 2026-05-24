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
import java.io.FileOutputStream

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
            val server = intent.getStringExtra("SERVER") ?: ""
            val port = intent.getStringExtra("PORT") ?: ""
            val uuid = intent.getStringExtra("UUID") ?: ""
            
            isRunning = true
            sendStateBroadcast(true)
            showNotification("Connecting to: $server")

            // تشغيل محرك الاتصال في خلفية مستقلة لكي لا يتوقف الإنترنت
            Thread {
                try {
                    startXrayEngine(server, port, uuid)
                    setupVpnInterface()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        return START_STICKY
    }

    private fun setupVpnInterface() {
        try {
            val builder = Builder()
            builder.setSession("DarkTunnelPro")
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0) // توجيه كل حركة إنترنت الهاتف عبر النفق الخاص بنا
            builder.addDnsServer("8.8.8.8")
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startXrayEngine(server: String, port: String, uuid: String) {
        try {
            // 1. استخراج ملف النواة التنفيذي من Assets إلى ذاكرة التطبيق الدائمة
            val xrayBinary = File(filesDir, "xray")
            if (!xrayBinary.exists()) {
                assets.open("xray").use { input ->
                    FileOutputStream(xrayBinary).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            // إعطاء صلاحيات تنفيذية للملف (ليعمل كمحرك)
            Runtime.getRuntime().exec("chmod 755 ${xrayBinary.absolutePath}").waitFor()

            // 2. تشغيل النواة في الخلفية وتمرير البيانات إليها
            val command = arrayOf(xrayBinary.absolutePath, "run") 
            xrayProcess = ProcessBuilder(*command).start()
            
            showNotification("DarkTunnel Pro is Active 🔑")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVpnService() {
        isRunning = false
        sendStateBroadcast(false)

        // قتل عملية Xray فوراً لقطع الاتصال ونظافة الخلفية
        try {
            xrayProcess?.destroy()
            xrayProcess = null
        } catch (e: Exception) {}

        // إغلاق نفق الـ VPN وإرجاع إنترنت الهاتف الطبيعي
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}

        stopForeground(true)
        stopSelf()
    }

    private fun showNotification(text: String) {
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "DARK_TUNNEL_CH")
                .setContentTitle("DarkTunnel Pro")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("DarkTunnel Pro")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
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
        val intent = Intent("COM.EXHXX.DARKTUNNEL.UPDATE_STATUS").apply {
            putExtra("RUNNING", running)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopVpnService()
        super.onDestroy()
    }
}
