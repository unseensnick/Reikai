package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class FlareSolverrUserAgentPinTest {

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sentUserAgents = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }
                        .firstOrNull { line -> line.startsWith("User-Agent:", ignoreCase = true) }
                        ?.let { line -> sentUserAgents += line.substringAfter(':').trim() }
                    it.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            }
        }
    }

    @AfterEach
    fun close() = server.close()

    @Test
    fun `a request retried from inside an interceptor after a solve carries the pinned User-Agent`() {
        val pins = ConcurrentHashMap<String, String>()
        // What a request queued behind a FlareSolverr solve does: it retries its own, pre-pin request.
        val waiter = Interceptor { chain ->
            chain.proceed(chain.request()).close()
            pins[chain.request().url.host] = "Pinned"
            chain.proceed(chain.request())
        }
        val client = OkHttpClient.Builder().pinFlareSolverrUserAgents(pins::get).addInterceptor(waiter).build()

        val request = Request.Builder()
            .url("http://${server.inetAddress.hostAddress}:${server.localPort}/")
            .header("User-Agent", "Default")
            .build()
        client.newCall(request).execute().close()

        sentUserAgents.last() shouldBe "Pinned"
    }
}
