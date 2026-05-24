package com.exhxx.darktunnel

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : Activity() {
    private lateinit var spProtocol: Spinner
    private lateinit var etServer: EditText
    private lateinit var etPort: EditText
    private lateinit var etUuid: EditText
    private lateinit var etPath: EditText
    private lateinit var etSni: EditText
    private lateinit var etProxy: EditText
    private lateinit var etPayload: EditText
    private lateinit var cbAutoConnect: CheckBox
    private lateinit var btnConnect: Button
    private lateinit var tvPing: TextView
    private lateinit var tvLogs: TextView
    private lateinit var logScroll: ScrollView

    private val pingHandler = Handler(Looper.getMainLooper())
    private lateinit var pingRunnable: Runnable
    private lateinit var logRunnable: Runnable

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra("RUNNING", false) ?: false
            updateUi(isRunning)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spProtocol = findViewById(R.id.spProtocol)
        etServer = findViewById(R.id.etServer)
        etPort = findViewById(R.id.etPort)
        etUuid = findViewById(R.id.etUuid)
        etPath = findViewById(R.id.etPath)
        etSni = findViewById(R.id.etSni)
        etProxy = findViewById(R.id.etProxy)
        etPayload = findViewById(R.id.etPayload)
        cbAutoConnect = findViewById(R.id.cbAutoConnect)
        btnConnect = findViewById(R.id.btnConnect)
        tvPing = findViewById(R.id.tvPing)
        tvLogs = findViewById(R.id.tvLogs)
        logScroll = findViewById(R.id.logScroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // برمجة القائمة المنسدلة
        val protocols = arrayOf("VLESS - TCP Direct (Payload)", "VLESS - WebSocket (SNI)", "Trojan + WS + Proxy (Payload)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocols)
        spProtocol.adapter = adapter

        val prefs = getSharedPreferences("DarkTunnelPrefs", Context.MODE_PRIVATE)
        spProtocol.setSelection(prefs.getInt("PROTOCOL_INDEX", 0))
        etServer.setText(prefs.getString("SERVER", ""))
        etPort.setText(prefs.getString("PORT", ""))
        etUuid.setText(prefs.getString("UUID", ""))
        etPath.setText(prefs.getString("PATH", "/"))
        etSni.setText(prefs.getString("SNI", ""))
        etProxy.setText(prefs.getString("PROXY", ""))
        etPayload.setText(prefs.getString("PAYLOAD", ""))
        cbAutoConnect.isChecked = prefs.getBoolean("AUTO_CONNECT", false)

        // إخفاء وإظهار الحقول بذكاء عند تغيير القائمة
        spProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(Color.WHITE)
                when (position) {
                    0 -> { // TCP
                        etUuid.hint = "UUID"
                        etPath.visibility = View.GONE
                        etSni.visibility = View.GONE
                        etProxy.visibility = View.GONE
                        etPayload.visibility = View.VISIBLE
                    }
                    1 -> { // WS SNI
                        etUuid.hint = "UUID"
                        etPath.visibility = View.VISIBLE
                        etSni.visibility = View.VISIBLE
                        etProxy.visibility = View.GONE
                        etPayload.visibility = View.GONE
                    }
                    2 -> { // Trojan
                        etUuid.hint = "Password"
                        etPath.visibility = View.VISIBLE
                        etSni.visibility = View.VISIBLE
                        etProxy.visibility = View.VISIBLE
                        etPayload.visibility = View.VISIBLE
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        pingRunnable = object : Runnable {
            override fun run() {
                if (TunnelVpnService.isRunning) {
                    Thread {
                        val pingResult = executePing()
                        runOnUiThread {
                            tvPing.text = "Ping: $pingResult"
                            tvPing.setTextColor(if (pingResult.contains("Timeout") || pingResult.contains("Error")) Color.parseColor("#FF5252") else Color.parseColor("#FFC107"))
                        }
                    }.start()
                    pingHandler.postDelayed(this, 2000)
                }
            }
        }

        logRunnable = object : Runnable {
            override fun run() {
                val logFile = File(filesDir, "xray_error.log")
                if (logFile.exists()) {
                    tvLogs.text = logFile.readText()
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
                pingHandler.postDelayed(this, 1500)
            }
        }
        pingHandler.post(logRunnable)

        updateUi(TunnelVpnService.isRunning)

        if (cbAutoConnect.isChecked && !TunnelVpnService.isRunning) {
            val intent = VpnService.prepare(this)
            if (intent == null) { startVpn() }
        }

        btnConnect.setOnClickListener {
            if (btnConnect.text.toString() == "DISCONNECT") {
                val stopIntent = Intent(this, TunnelVpnService::class.java).apply { action = "ACTION_STOP" }
                startService(stopIntent)
                updateUi(false)
            } else {
                val editor = prefs.edit()
                editor.putInt("PROTOCOL_INDEX", spProtocol.selectedItemPosition)
                editor.putString("SERVER", etServer.text.toString())
                editor.putString("PORT", etPort.text.toString())
                editor.putString("UUID", etUuid.text.toString())
                editor.putString("PATH", etPath.text.toString())
                editor.putString("SNI", etSni.text.toString())
                editor.putString("PROXY", etProxy.text.toString())
                editor.putString("PAYLOAD", etPayload.text.toString())
                editor.putBoolean("AUTO_CONNECT", cbAutoConnect.isChecked)
                editor.apply()

                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 1)
                } else {
                    startVpn()
                }
            }
        }
    }

    private fun executePing(): String {
        try {
            val process = Runtime.getRuntime().exec("ping -c 1 -W 1 8.8.8.8")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("time=")) return "${line!!.substringAfter("time=").substringBefore(" ms")} ms"
            }
            return "Timeout"
        } catch (e: Exception) { return "Error" }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, IntentFilter("COM.EXHXX.DARKTUNNEL.UPDATE_STATUS"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, IntentFilter("COM.EXHXX.DARKTUNNEL.UPDATE_STATUS"))
        }
        updateUi(TunnelVpnService.isRunning)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(vpnStateReceiver)
    }

    private fun startVpn() {
        updateUi(true)
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = "ACTION_START"
            putExtra("PROTOCOL", spProtocol.selectedItemPosition)
            putExtra("SERVER", etServer.text.toString())
            putExtra("PORT", etPort.text.toString())
            putExtra("UUID", etUuid.text.toString())
            putExtra("PATH", etPath.text.toString())
            putExtra("SNI", etSni.text.toString())
            putExtra("PROXY", etProxy.text.toString())
            putExtra("PAYLOAD", etPayload.text.toString())
        }
        startService(intent)
    }

    private fun updateUi(isRunning: Boolean) {
        val alpha = if (isRunning) 0.5f else 1.0f
        arrayOf(spProtocol, etServer, etPort, etUuid, etPath, etSni, etProxy, etPayload, cbAutoConnect).forEach { 
            it.isEnabled = !isRunning 
            it.alpha = alpha
        }

        if (isRunning) {
            btnConnect.text = "DISCONNECT"
            btnConnect.setBackgroundColor(Color.parseColor("#D32F2F"))
            btnConnect.setTextColor(Color.WHITE)
            pingHandler.post(pingRunnable)
        } else {
            btnConnect.text = "CONNECT"
            btnConnect.setBackgroundColor(Color.parseColor("#FFC107"))
            btnConnect.setTextColor(Color.parseColor("#1A1A1A"))
            pingHandler.removeCallbacks(pingRunnable)
            tvPing.text = "Ping: -- ms"
            tvPing.setTextColor(Color.parseColor("#FFC107"))
        }
    }
}
