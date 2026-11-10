package eu.kanade.tachiyomi.network.interceptor

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** A server only gets a request it can send as asked: Byparr turns a POST into a GET of the same url. */
class FlareSolverrCarriesTest {

    // Nothing listens on port 1, so no banner vouches for the server, as with Byparr's redirect to its docs.
    private val client = FlareSolverrClient(
        mockk(relaxed = true),
        NetworkPreferences(InMemoryPreferenceStore(), false).apply { flareSolverrUrl.set("http://127.0.0.1:1") },
    )

    @Test
    fun `a POST stays with the WebView when no FlareSolverr banner vouches for the server`() {
        val post = Request.Builder().url("https://example.org/search").post(FormBody.Builder().add("q", "x").build())

        client.carries(post.build()) shouldBe false
    }

    @Test
    fun `a GET goes to the server whatever its banner says`() {
        client.carries(Request.Builder().url("https://example.org/").build()) shouldBe true
    }

    // The solvers have only a GET and a POST command, so the write would go out as a GET.
    @ParameterizedTest
    @ValueSource(strings = ["PUT", "PATCH", "DELETE"])
    fun `a write other than a POST never goes to the server`(method: String) {
        val write = Request.Builder().url("https://example.org/progress").method(method, "{}".toRequestBody())

        client.carries(write.build()) shouldBe false
    }

    @Test
    fun `a POST body never crosses the internet in the clear, even to a FlareSolverr`() {
        val public = spyk(
            FlareSolverrClient(
                mockk(relaxed = true),
                NetworkPreferences(
                    InMemoryPreferenceStore(sequenceOf(InMemoryPreference(FLARESOLVERR_URL_KEY, PUBLIC_URL, ""))),
                    false,
                ),
            ),
            recordPrivateCalls = true,
        )
        every { public["speaksFlareSolverr"](any<String>()) } returns true
        val post = Request.Builder().url("https://example.org/login").post(FormBody.Builder().add("pw", "x").build())

        public.carries(post.build()) shouldBe false
    }

    @Test
    fun `a probe the server failed is asked again rather than kept`() {
        // A proxy answers 502 while the solver behind it boots, then the solver's banner.
        val answers = ArrayDeque(listOf(502 to "Bad Gateway", 200 to """{"msg": "FlareSolverr is ready!"}"""))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val (code, body) = answers.removeFirst()
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        var clock = 0L
        val booting = FlareSolverrClient(
            mockk(relaxed = true),
            NetworkPreferences(InMemoryPreferenceStore(), false).apply {
                flareSolverrUrl.set("http://127.0.0.1:${server.address.port}")
            },
        ).apply { now = { clock } }
        val post = Request.Builder().url("https://example.org/search").post(FormBody.Builder().add("q", "x").build())
        try {
            booting.carries(post.build())
            clock += 30_000L

            booting.carries(post.build()) shouldBe true
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a server that gives no answer is probed once while it waits, not on every POST`() {
        val probes = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                probes.incrementAndGet()
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val down = FlareSolverrClient(
            mockk(relaxed = true),
            NetworkPreferences(InMemoryPreferenceStore(), false).apply {
                flareSolverrUrl.set("http://127.0.0.1:${server.address.port}")
            },
        )
        val post = Request.Builder().url("https://example.org/search").post(FormBody.Builder().add("q", "x").build())
        try {
            down.carries(post.build())
            down.carries(post.build())

            probes.get() shouldBe 1
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        const val PUBLIC_URL = "http://solver.example.com:8191"
    }
}
