package com.exhxx.darktunnel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var etServer: EditText
    private lateinit var etPort: EditText
    private lateinit var etUuid: EditText
    private lateinit var etPayload: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.etServer)
        etPort = findViewById(R.id.etPort)
        etUuid = findViewById(R.id.etUuid)
        etPayload = findViewById(R.id.etPayload)

        // تحميل الإعدادات المحفوظة تلقائياً عند فتح التطبيق
        val prefs = getSharedPreferences("DarkTunnelPrefs", Context.MODE_PRIVATE)
        etServer.setText(prefs.getString("SERVER", ""))
        etPort.setText(prefs.getString("PORT", ""))
        etUuid.setText(prefs.getString("UUID", ""))
        etPayload.setText(prefs.getString("PAYLOAD", ""))

        val btnConnect = findViewById<Button>(R.id.btnConnect)
        btnConnect.setOnClickListener {
            // حفظ الإعدادات فوراً في ذاكرة الهاتف الدائمة كي لا تختفي
            val editor = prefs.edit()
            editor.putString("SERVER", etServer.text.toString())
            editor.putString("PORT", etPort.text.toString())
            editor.putString("UUID", etUuid.text.toString())
            editor.putString("PAYLOAD", etPayload.text.toString())
            editor.apply()

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
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            putExtra("SERVER", etServer.text.toString())
            putExtra("PORT", etPort.text.toString())
            putExtra("UUID", etUuid.text.toString())
            putExtra("PAYLOAD", etPayload.text.toString())
        }
        startService(intent)
        Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
    }
}
