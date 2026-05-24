package com.haider.tunnel.proxy

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.ServerSocket

class ProxyManager(
    private val server: String,
    private val port: Int,
    private val uuid: String,
    private val payload: String
) {
    private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        running = true
        serverSocket = ServerSocket(8080)
        Thread {
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

            // إرسال الـ Payload
            val fullPayload = payload
                .replace("[crlf]", "\r\n")
                .replace("\\r\\n", "\r\n") + "\r\n"

            remote.getOutputStream().write(fullPayload.toByteArray())
            remote.getOutputStream().flush()

            // قراءة رد السيرفر (200 OK)
            val buffer = ByteArray(1024)
            val read = remote.getInputStream().read(buffer)
            val response = String(buffer, 0, read)

            if (response.contains("200") || response.contains("300")) {
                // بدء تمرير البيانات
                val t1 = Thread { pipe(client.getInputStream(), remote.getOutputStream()) }
                val t2 = Thread { pipe(remote.getInputStream(), client.getOutputStream()) }
                t1.start()
                t2.start()
                t1.join()
                t2.join()
            }

            remote.close()
            client.close()
        } catch (e: Exception) {
            e.printStackTrace()
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
        serverSocket?.close()
    }
}
