package eu.kanade.tachiyomi.network.interceptor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fetches through real WebViews against loopback servers, each port its own origin and so its own page. */
@RunWith(AndroidJUnit4::class)
class WebViewFetcherTest {

    private val fetcher = WebViewFetcher(InstrumentationRegistry.getInstrumentation().targetContext) { false }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            when (val outcome = fetcher.fetch(chain, chain.request())) {
                is WebViewFetcher.Outcome.Served -> outcome.response
                is WebViewFetcher.Outcome.Failed -> throw outcome.error
                WebViewFetcher.Outcome.Challenged -> throw IOException("challenged")
            }
        }
        .build()

    @Test
    fun aPageStillOpeningIsNotEvictedForAnotherSite() {
        // One more site than the page cap, all at once, so the last page opens while the first waits to be ready.
        val servers = List(5) { Server { _, _ -> ok("site") } }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(servers.size)
        val codes = servers.map { server ->
            pool.submit<Int> {
                start.await()
                runCatching { client.newCall(Request.Builder().url(server.url("x")).build()).execute().use { it.code } }
                    .getOrDefault(-1)
            }
        }
        start.countDown()
        val answered = codes.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        servers.forEach(Server::close)

        assertEquals(List(5) { 200 }, answered)
    }

    @Test
    fun aRedirectThatAnswersOnlyToTheRequestsHeadersIsStillFollowed() {
        val landing = Server { _, _ -> ok("landed") }
        val keyed = Server { _, headers ->
            if (headers.any { it.equals("x-key: secret", ignoreCase = true) }) {
                "HTTP/1.1 302 Found\r\nLocation: ${landing.url("there")}\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            } else {
                ok("no key")
            }
        }
        val request = Request.Builder().url(keyed.url("here")).header("X-Key", "secret").build()

        val body = client.newCall(request).execute().use { it.body.string() }
        keyed.close()
        landing.close()

        assertEquals("landed", body)
    }

    private fun ok(body: String) =
        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\n" +
            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    /** Answers each request with [answer] given its request line and header lines, a thread per connection. */
    private class Server(private val answer: (String, List<String>) -> String) : Closeable {
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
    }
}
