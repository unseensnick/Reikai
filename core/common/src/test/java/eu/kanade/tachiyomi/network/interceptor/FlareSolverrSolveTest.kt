package eu.kanade.tachiyomi.network.interceptor

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** [FlareSolverrClient.resolve] against a stand-in solver on the loopback address. */
class FlareSolverrSolveTest {

    private val banners = ArrayDeque<Int>()
    private val commands = CopyOnWriteArrayList<String>()

    @Volatile
    private var solution = """{"url": "https://site.example/", "status": 200, "response": "page"}"""

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/v1") {
                val command = exchange.requestBody.readBytes().decodeToString().also { commands += it }
                val created = "sessions.create" in command
                if (created) """{"status": "ok"}""" else """{"status": "ok", "solution": $solution}"""
            } else {
                """{"msg": "FlareSolverr is ready!"}"""
            }
            val code = if (exchange.requestURI.path == "/") banners.removeFirstOrNull() ?: 200 else 200
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val client = FlareSolverrClient(
        mockk(relaxed = true),
        NetworkPreferences(InMemoryPreferenceStore(), false).apply {
            flareSolverrUrl.set("http://127.0.0.1:${server.address.port}")
        },
    )

    private val page = Request.Builder().url("https://site.example/").build()

    @AfterEach
    fun stop() = server.stop(0)

    private fun resolve(request: Request = page) = client.resolve("http://127.0.0.1:${server.address.port}", request)

    @Test
    fun `a banner probe the server failed leaves only that solve without a session`() {
        // A proxy answers 502 while the solver behind it boots.
        banners += 502
        resolve().close()

        resolve().close()

        commands.last() shouldContain "\"session\""
    }

    @Test
    fun `the site's own refusal is served as its answer`() {
        solution = """{"url": "https://site.example/wp-admin/admin-ajax.php", "status": 400, "response": "0"}"""

        resolve().use { it.code } shouldBe 400
    }

    @Test
    fun `the answer carries the page the server's browser was redirected to`() {
        solution = """{"url": "https://site.example/novel/one/", "status": 200, "response": "page"}"""
        val permalink = Request.Builder().url("https://site.example/?p=12").head().build()

        resolve(permalink).use { it.request.url.toString() } shouldBe "https://site.example/novel/one/"
    }
}
