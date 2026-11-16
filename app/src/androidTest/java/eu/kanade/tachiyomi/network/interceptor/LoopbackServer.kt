package eu.kanade.tachiyomi.network.interceptor

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Answers each request with [answer] given its request line and header lines, a thread per connection. */
class LoopbackServer(private val answer: (String, List<String>) -> String) : Closeable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    fun url(path: String) = "http://127.0.0.1:${socket.localPort}/$path"

    init {
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                Thread { runCatching { respond(client) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun respond(client: Socket) = client.use {
        val reader = client.getInputStream().bufferedReader()
        val line = reader.readLine().orEmpty()
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        client.getOutputStream().apply {
            write(answer(line, headers).toByteArray())
            flush()
        }
    }

    override fun close() = socket.close()

    companion object {
        fun ok(body: String, contentType: String = "text/plain") =
            "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
    }
}
