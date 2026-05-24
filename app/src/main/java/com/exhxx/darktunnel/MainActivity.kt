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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {
    private lateinit var etServer: EditText
    private lateinit var etPort: EditText
    private lateinit var etUuid: EditText
    private lateinit var etPayload: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvPing: TextView

    private val pingHandler = Handler(Looper.getMainLooper())
    private lateinit var pingRunnable: Runnable

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra("RUNNING", false) ?: false
            updateUi(isRunning)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.etServer)
        etPort = findViewById(R.id.etPort)
        etUuid = findViewById(R.id.etUuid)
        etPayload = findViewById(R.id.etPayload)
        btnConnect = findViewById(R.id.btnConnect)
        tvPing = findViewById(R.id.tvPing)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val prefs = getSharedPreferences("DarkTunnelPrefs", Context.MODE_PRIVATE)
        etServer.setText(prefs.getString("SERVER", ""))
        etPort.setText(prefs.getString("PORT", ""))
        etUuid.setText(prefs.getString("UUID", ""))
        etPayload.setText(prefs.getString("PAYLOAD", ""))

        // برمجة البنج ليعمل في الخلفية ويحدث الشاشة كل ثانيتين
        pingRunnable = object : Runnable {
            override fun run() {
                if (TunnelVpnService.isRunning) {
                    Thread {
                        val pingResult = executePing()
                        runOnUiThread {
                            tvPing.text = "Ping: $pingResult"
                            if (pingResult.contains("Timeout") || pingResult.contains("Error")) {
                                tvPing.setTextColor(Color.parseColor("#FF5252")) // أحمر إذا فشل
                            } else {
                                tvPing.setTextColor(Color.parseColor("#00E676")) // أخضر إذا نجح
                            }
                        }
                    }.start()
                    pingHandler.postDelayed(this, 2000)
                }
            }
        }

        updateUi(TunnelVpnService.isRunning)

        btnConnect.setOnClickListener {
            if (btnConnect.text.toString() == "DISCONNECT") {
                val stopIntent = Intent(this, TunnelVpnService::class.java).apply {
                    action = "ACTION_STOP"
                }
                startService(stopIntent)
                updateUi(false)
            } else {
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
    }

    private fun executePing(): String {
        try {
            val process = Runtime.getRuntime().exec("ping -c 1 -W 1 8.8.8.8")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("time=")) {
                    val time = line!!.substringAfter("time=").substringBefore(" ms")
                    return "$time ms"
                }
            }
            return "Timeout"
        } catch (e: Exception) {
            return "Error"
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "VPN Permission Denied!", Toast.LENGTH_SHORT).show()
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVpn() {
        updateUi(true)
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = "ACTION_START"
            putExtra("SERVER", etServer.text.toString())
            putExtra("PORT", etPort.text.toString())
            putExtra("UUID", etUuid.text.toString())
            putExtra("PAYLOAD", etPayload.text.toString())
        }
        startService(intent)
    }

    private fun updateUi(isRunning: Boolean) {
        // قفل أو فتح الحقول بناءً على حالة الاتصال
        etServer.isEnabled = !isRunning
        etPort.isEnabled = !isRunning
        etUuid.isEnabled = !isRunning
        etPayload.isEnabled = !isRunning

        // تعتيم الحقول قليلاً عند القفل لتبدو احترافية
        val alpha = if (isRunning) 0.5f else 1.0f
        etServer.alpha = alpha
        etPort.alpha = alpha
        etUuid.alpha = alpha
        etPayload.alpha = alpha

        if (isRunning) {
            btnConnect.text = "DISCONNECT"
            btnConnect.setBackgroundColor(Color.parseColor("#D32F2F")) // زر أحمر
            pingHandler.post(pingRunnable) // تشغيل البنج
        } else {
            btnConnect.text = "CONNECT"
            btnConnect.setBackgroundColor(Color.parseColor("#8A2BE2")) // زر بنفسجي
            pingHandler.removeCallbacks(pingRunnable) // إيقاف البنج
            tvPing.text = "Ping: -- ms"
            tvPing.setTextColor(Color.parseColor("#00FF00"))
        }
    }
}
