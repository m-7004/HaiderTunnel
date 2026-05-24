package com.exhxx.darktunnel

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
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
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVpn() {
        val intent = Intent(this, TunnelVpnService::class.java)
        startService(intent)
        Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
    }
}
