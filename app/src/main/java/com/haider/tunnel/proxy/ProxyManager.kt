package com.haider.tunnel.proxy

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class ProxyManager(
    private val server: String,
    private val port: Int,
    private val uuid: String,
    private val payload: String
) {
    private var running = false
    private var serverSocket: ServerSocket? = null
    val localPort = 1080

    fun start() {
        running = true
        Thread {
            serverSocket = ServerSocket(localPort)
            while (running) {
                try {
                    val client = serverSocket!!.accept()
                    Thread { handleClient(client) }.start()
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }.start()
    }

    private fun handleClient(client: Socket) {
        try {
            val remote = Socket(server, port)
            remote.soTimeout = 10000

            // إرسال الـ Payload الكامل
            val fullPayload = payload
                .replace("[crlf]", "\r\n")
                .replace("\\r\\n", "\r\n") + "\r\n\r\n"

            remote.getOutputStream().write(fullPayload.toByteArray(Charsets.ISO_8859_1))
            remote.getOutputStream().flush()

            // قراءة رد السيرفر
            val buffer = ByteArray(4096)
            val read = remote.getInputStream().read(buffer)
            if (read > 0) {
                val response = String(buffer, 0, read, Charsets.ISO_8859_1)
                if (response.contains("200") || response.contains("300")) {
                    remote.soTimeout = 0
                    val t1 = Thread { pipe(client.getInputStream(), remote.getOutputStream()) }
                    val t2 = Thread { pipe(remote.getInputStream(), client.getOutputStream()) }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                }
            }
            remote.close()
            client.close()
        } catch (e: Exception) {
            try { client.close() } catch (ex: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: Exception) {}
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
