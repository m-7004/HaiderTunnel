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
import android.text.Html
import android.view.View
import android.widget.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var etServer: EditText
    private lateinit var etUuid: EditText
    private lateinit var etPayload: EditText
    private lateinit var cbAutoConnect: CheckBox
    private lateinit var btnConnect: Button
    private lateinit var btnClearLogs: ImageView
    private lateinit var tvLogs: TextView
    private lateinit var logScroll: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var pingRunnable: Runnable
    private var isVerifying = false

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra("RUNNING", false) ?: false
            val statusMsg = intent?.getStringExtra("MSG") ?: ""
            
            isVerifying = false
            if (isRunning) {
                triggerConnectedLogs()
            } else {
                if (statusMsg == "FAILED") {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    appendHtmlLog("<font color='#FF5252'>Unable to detect internet connection [$time]</font><br/>")
                } else if (statusMsg == "DISCONNECTED") {
                    triggerDisconnectLogs()
                }
            }
            updateUi(isRunning)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.etServer)
        etUuid = findViewById(R.id.etUuid)
        etPayload = findViewById(R.id.etPayload)
        cbAutoConnect = findViewById(R.id.cbAutoConnect)
        btnConnect = findViewById(R.id.btnConnect)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvLogs = findViewById(R.id.tvLogs)
        logScroll = findViewById(R.id.logScroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val prefs = getSharedPreferences("DarkTunnelPrefs", Context.MODE_PRIVATE)
        etServer.setText(prefs.getString("SERVER", ""))
        etUuid.setText(prefs.getString("UUID", ""))
        etPayload.setText(prefs.getString("PAYLOAD", ""))
        cbAutoConnect.isChecked = prefs.getBoolean("AUTO_CONNECT", false)

        btnClearLogs.setOnClickListener { tvLogs.text = "" }

        // فحص البنج الحقيقي من داخل بروكسي الـ Xray (127.0.0.1:10809)
        pingRunnable = object : Runnable {
            override fun run() {
                if (TunnelVpnService.isRunning) {
                    Thread {
                        val pingMs = executeProxyPing()
                        runOnUiThread {
                            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            if (pingMs != -1) {
                                val color = if (pingMs <= 100) "#00E676" else "#FF5252"
                                appendHtmlLog("HTTP Ping 200 OK (<font color='$color'>${pingMs}ms</font>) [$time]<br/>")
                            } else {
                                appendHtmlLog("Unable to detect internet connection [$time]<br/>")
                            }
                        }
                    }.start()
                    mainHandler.postDelayed(this, 2500)
                }
            }
        }

        updateUi(TunnelVpnService.isRunning)

        btnConnect.setOnClickListener {
            if (btnConnect.text.toString() == "DISCONNECT") {
                val stopIntent = Intent(this, TunnelVpnService::class.java).apply { action = "ACTION_STOP" }
                startService(stopIntent)
                updateUi(false)
            } else {
                val editor = prefs.edit()
                editor.putString("SERVER", etServer.text.toString())
                editor.putString("UUID", etUuid.text.toString())
                editor.putString("PAYLOAD", etPayload.text.toString())
                editor.putBoolean("AUTO_CONNECT", cbAutoConnect.isChecked)
                editor.apply()

                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 1)
                } else {
                    isVerifying = true
                    updateUi(false)
                    triggerConnectingLogs()
                    startVpn()
                }
            }
        }
    }

    private fun executeProxyPing(): Int {
        try {
            val start = System.currentTimeMillis()
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 10809))
            val url = URL("http://cp.cloudflare.com/generate_204")
            val conn = url.openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (conn.responseCode == 204 || conn.responseCode == 200) {
                return (System.currentTimeMillis() - start).toInt()
            }
        } catch (e: Exception) {}
        return -1
    }

    private fun appendHtmlLog(htmlText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tvLogs.append(Html.fromHtml(htmlText, Html.FROM_HTML_MODE_LEGACY))
        } else {
            tvLogs.append(Html.fromHtml(htmlText))
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun triggerConnectingLogs() {
        tvLogs.text = ""
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        appendHtmlLog("Connecting to ${etServer.text} [$time]<br/>")
    }

    private fun triggerConnectedLogs() {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        appendHtmlLog("Connection established [$time]<br/>")
        appendHtmlLog("<font color='#00E676'>Connected [$time]</font><br/>")
    }

    private fun triggerDisconnectLogs() {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        appendHtmlLog("Closing client connection [$time]<br/>")
        appendHtmlLog("<font color='#FF5252'>Disconnected [$time]</font><br/>")
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
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = "ACTION_START"
            putExtra("SERVER", etServer.text.toString())
            putExtra("UUID", etUuid.text.toString())
            putExtra("PAYLOAD", etPayload.text.toString())
        }
        startService(intent)
    }

    private fun updateUi(isRunning: Boolean) {
        val alpha = if (isRunning || isVerifying) 0.5f else 1.0f
        arrayOf(etServer, etUuid, etPayload, cbAutoConnect).forEach { 
            it.isEnabled = ! (isRunning || isVerifying)
            it.alpha = alpha
        }

        if (isRunning) {
            btnConnect.text = "DISCONNECT"
            btnConnect.setBackgroundColor(Color.parseColor("#1C1C1E"))
            btnConnect.setTextColor(Color.parseColor("#FF5252"))
            mainHandler.post(pingRunnable)
        } else if (isVerifying) {
            btnConnect.text = "CONNECTING..."
            btnConnect.setBackgroundColor(Color.parseColor("#555555"))
            btnConnect.setTextColor(Color.WHITE)
            mainHandler.removeCallbacks(pingRunnable)
        } else {
            btnConnect.text = "CONNECT"
            btnConnect.setBackgroundColor(Color.parseColor("#B388FF"))
            btnConnect.setTextColor(Color.WHITE)
            mainHandler.removeCallbacks(pingRunnable)
        }
    }
}
