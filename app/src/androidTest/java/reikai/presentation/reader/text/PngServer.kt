package reikai.presentation.reader.text

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves one PNG over loopback. The native renderer fetches through the app's own Coil loader, which
 * has no seam a test can stand in at the way a WebView's client is one, so the image comes off a
 * socket. Each connection is answered on its own thread, so a delayed picture holds back no other.
 */
internal class PngServer(
    private val png: ByteArray,
    /** Requests answered 404 before the picture is served, as a host that fails and then recovers does. */
    failFirst: Int = 0,
) : Closeable {

    private val failuresLeft = AtomicInteger(failFirst)

    /** Serves the picture from the next request on. */
    fun recover() = failuresLeft.set(0)

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val url = url("picture")

    /** Pictures written out in full, which is all a renderer that never lays a chapter out can be waited on by. */
    val served = AtomicInteger()

    /** A distinct address per [name], so no cache answers one for another, served after [delayMs]. */
    fun url(name: String, delayMs: Long = 0) = "http://127.0.0.1:${socket.localPort}/$name.png?delay=$delayMs"

    init {
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                Thread { runCatching { answer(client) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun answer(client: java.net.Socket) = client.use {
        val reader = client.getInputStream().bufferedReader()
        val request = reader.readLine().orEmpty()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        Regex("delay=(\\d+)").find(request)?.groupValues?.get(1)?.toLong()?.let(Thread::sleep)
        if (failuresLeft.getAndDecrement() > 0) {
            client.getOutputStream().apply {
                write(
                    "HTTP/1.1 404 Not Found\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                flush()
            }
            return@use
        }
        client.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                        "Content-Length: ${png.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            write(png)
            flush()
        }
        served.incrementAndGet()
    }

    override fun close() = socket.close()
}

internal fun pngOf(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
}.toByteArray()
