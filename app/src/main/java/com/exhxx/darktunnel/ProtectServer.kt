package com.exhxx.darktunnel

import android.net.VpnService
import android.util.Log
import java.io.File
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.InputStream
import java.nio.ByteBuffer

class ProtectServer(
    private val vpnService: VpnService,
    private val socketPath: String
) {
    private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        File(socketPath).delete()

        thread = Thread {
            try {
                serverSocket = LocalServerSocket(socketPath)
                while (running) {
                    val client = serverSocket!!.accept() ?: continue
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running) Log.e("ProtectServer", "Error: \${e.message}")
            }
        }.also { it.start() }
    }

    private fun handleClient(client: LocalSocket) {
        Thread {
            try {
                val input: InputStream = client.inputStream
                val fds = client.ancillaryFileDescriptors
                if (fds != null && fds.isNotEmpty()) {
                    val fd = fds[0]
                    val success = vpnService.protect(fd)
                    client.outputStream.write(if (success) 0 else 1)
                    client.outputStream.flush()
                } else {
                    val buf = ByteArray(4)
                    val read = input.read(buf)
                    if (read == 4) {
                        val fdInt = ByteBuffer.wrap(buf).int
                        val success = vpnService.protect(fdInt)
                        client.outputStream.write(if (success) 0 else 1)
                        client.outputStream.flush()
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { client.close() } catch (ignored: Exception) {}
            }
        }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (ignored: Exception) {}
        thread?.interrupt()
        File(socketPath).delete()
    }
}
