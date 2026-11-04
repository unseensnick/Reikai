package eu.kanade.tachiyomi.network.interceptor

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import okhttp3.FormBody
import okhttp3.Request
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.net.InetSocketAddress

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
        val booting = FlareSolverrClient(
            mockk(relaxed = true),
            NetworkPreferences(InMemoryPreferenceStore(), false).apply {
                flareSolverrUrl.set("http://127.0.0.1:${server.address.port}")
            },
        )
        val post = Request.Builder().url("https://example.org/search").post(FormBody.Builder().add("q", "x").build())
        try {
            booting.carries(post.build())

            booting.carries(post.build()) shouldBe true
        } finally {
            server.stop(0)
        }
    }
}
