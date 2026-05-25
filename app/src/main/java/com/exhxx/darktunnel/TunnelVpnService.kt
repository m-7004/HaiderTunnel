package com.exhxx.darktunnel

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

class TunnelVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverInput = intent?.getStringExtra("SERVER") ?: ""
        val uuid = intent?.getStringExtra("UUID") ?: ""
        val payloadRaw = intent?.getStringExtra("PAYLOAD") ?: ""

        Thread {
            startXrayEngine(serverInput, uuid, payloadRaw)
            
            val builder = Builder()
            builder.setSession("exhxx_Pro")
            builder.setMtu(1400)
            builder.addAddress("10.0.0.2", 24)
            // هذا السطر هو السر: يجبر كل بيانات الموبايل تدخل بالنفق
            builder.addRoute("0.0.0.0", 0) 
            builder.addDnsServer("1.1.1.1")
            
            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
            
            vpnInterface = builder.establish()
        }.start()
        
        return START_STICKY
    }

    private fun startXrayEngine(serverInput: String, uuid: String, payloadRaw: String) {
        val xrayPath = applicationInfo.nativeLibraryDir + "/libxray.so"
        File(xrayPath).setExecutable(true)
        
        // إعداد الكونفج ليكون Transparent (مرور شامل لكل البيانات)
        val config = """
        {
          "log": { "loglevel": "none" },
          "inbounds": [ { "port": 10808, "protocol": "socks", "sniffing": { "enabled": true, "destOverride": ["http", "tls"] } } ],
          "outbounds": [ { "protocol": "vless", "settings": { "vnext": [ { "address": "${serverInput.split(":")[0]}", "port": ${serverInput.split(":")[1].toInt()}, "users": [ { "id": "$uuid", "encryption": "none" } ] } ] }, "streamSettings": { "network": "tcp", "tcpSettings": { "header": { "type": "http", "request": { "method": "GET", "path": ["/"], "headers": { "Host": ["${serverInput.split(":")[0]}"] } } } } } } ]
        }
        """.trimIndent()
        
        val configFile = File(filesDir, "config.json")
        configFile.writeText(config)
        xrayProcess = ProcessBuilder(xrayPath, "-c", configFile.absolutePath).start()
    }

    override fun onDestroy() {
        xrayProcess?.destroy()
        vpnInterface?.close()
        super.onDestroy()
    }
}
