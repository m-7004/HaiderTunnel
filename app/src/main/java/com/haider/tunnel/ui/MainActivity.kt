package com.haider.tunnel.ui

import android.app.Activity
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

    companion object {
        private const val VPN_REQUEST_CODE = 100
    }

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
            if (!isConnected) requestVpnPermission() else stopVpn()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpn()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startVpn()
            } else {
                tvStatus.text = "تم رفض إذن VPN"
                tvStatus.setTextColor(0xFFFF4444.toInt())
            }
        }
    }

    private fun startVpn() {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            putExtra("server", etServer.text.toString().trim())
            putExtra("port", etPort.text.toString().toIntOrNull() ?: 80)
            putExtra("uuid", etUuid.text.toString().trim())
            putExtra("payload", etPayload.text.toString().trim())
        }
        startService(intent)
        isConnected = true
        tvStatus.text = "متصل ✓"
        tvStatus.setTextColor(0xFF44FF44.toInt())
        btnConnect.text = "DISCONNECT"
    }

    private fun stopVpn() {
        stopService(Intent(this, TunnelVpnService::class.java))
        isConnected = false
        tvStatus.text = "غير متصل"
        tvStatus.setTextColor(0xFFFF4444.toInt())
        btnConnect.text = "CONNECT"
    }
}
