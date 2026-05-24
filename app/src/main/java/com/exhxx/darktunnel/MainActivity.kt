package com.exhxx.darktunnel

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnConnect = findViewById<Button>(R.id.btnConnect)
        btnConnect.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 1)
            } else {
                startVpn()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "VPN Permission Denied!", Toast.LENGTH_SHORT).show()
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVpn() {
        // سحب البيانات من الواجهة
        val server = findViewById<EditText>(R.id.etServer).text.toString()
        val port = findViewById<EditText>(R.id.etPort).text.toString()
        val uuid = findViewById<EditText>(R.id.etUuid).text.toString()
        val payload = findViewById<EditText>(R.id.etPayload).text.toString()

        // إرسالها إلى محرك الـ VPN
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            putExtra("SERVER", server)
            putExtra("PORT", port)
            putExtra("UUID", uuid)
            putExtra("PAYLOAD", payload)
        }
        startService(intent)
        Toast.makeText(this, "Connecting to $server...", Toast.LENGTH_SHORT).show()
    }
}
