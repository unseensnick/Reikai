package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
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

    // A WebView solve earns its clearance under the request's own User-Agent, so a pin left
    // standing once FlareSolverr is off sends the retry under another one and Cloudflare refuses it.
    @Test
    fun `a solved host is not pinned once FlareSolverr is turned off`() {
        val client = solver(enabled = false, url = SOLVER_URL).apply { pin("a.example", "FS-UA") }

        client.pinnedUserAgentFor("a.example").shouldBeNull()
    }

    @Test
    fun `a solved host is not pinned once the solver's address is cleared`() {
        val client = solver(enabled = true, url = " ").apply { pin("a.example", "FS-UA") }

        client.pinnedUserAgentFor("a.example").shouldBeNull()
    }

    @Test
    fun `a solved host keeps its pin while FlareSolverr is in use`() {
        val client = solver(enabled = true, url = SOLVER_URL).apply { pin("a.example", "FS-UA") }

        client.pinnedUserAgentFor("a.example") shouldBe "FS-UA"
    }

    // Seeded through the constructor: an in-memory preference cannot be written afterwards.
    private fun solver(enabled: Boolean, url: String) = FlareSolverrClient(
        mockk(relaxed = true),
        NetworkPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreference("enable_flaresolverr", enabled, false),
                    InMemoryPreference(FLARESOLVERR_URL_KEY, url, ""),
                ),
            ),
            false,
        ),
    )

    private companion object {
        const val SOLVER_URL = "http://solver.example:8191"
    }
}
