package com.haider.tunnel.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.haider.tunnel.R
import com.haider.tunnel.service.TunnelVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etPort: EditText
    private lateinit var etUuid: EditText
    private lateinit var etPayload: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.et_server)
        etPort = findViewById(R.id.et_port)
        etUuid = findViewById(R.id.et_uuid)
        etPayload = findViewById(R.id.et_payload)
        tvStatus = findViewById(R.id.tv_status)
        btnConnect = findViewById(R.id.btn_connect)

        btnConnect.setOnClickListener {
            if (!isConnected) startVpn() else stopVpn()
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 100)
        } else {
            onActivityResult(100, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val intent = Intent(this, TunnelVpnService::class.java).apply {
                putExtra("server", etServer.text.toString())
                putExtra("port", etPort.text.toString().toIntOrNull() ?: 80)
                putExtra("uuid", etUuid.text.toString())
                putExtra("payload", etPayload.text.toString())
            }
            startService(intent)
            isConnected = true
            tvStatus.text = "متصل ✓"
            tvStatus.setTextColor(0xFF44FF44.toInt())
            btnConnect.text = "DISCONNECT"
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, TunnelVpnService::class.java)
        stopService(intent)
        isConnected = false
        tvStatus.text = "غير متصل"
        tvStatus.setTextColor(0xFFFF4444.toInt())
        btnConnect.text = "CONNECT"
    }
}
