package reikai.novel.host

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Plugins hand the host a JSON `FetchInit`. When strict decoding fails (a field has a shape the
 * serializer rejects), the bridge falls back to picking out the fields it understands rather than
 * aborting the whole fetch. These pin that lenient fallback.
 */
class LnHostBridgeTest {

    private val bridge = LnHostBridge(preferenceStore = mockk(relaxed = true), client = mockk(relaxed = true))

    @Test
    fun `blank options decode to defaults`() {
        bridge.parseFetchOpts("") shouldBe LnHostBridge.FetchOpts()
    }

    @Test
    fun `well-formed options keep their headers`() {
        bridge.parseFetchOpts("""{"method":"GET","headers":{"A":"1"}}""").headers shouldBe mapOf("A" to "1")
    }

    @Test
    fun `a malformed headers shape drops headers but keeps method and body`() {
        // headers is an array, not an object, so strict decode fails and the manual fallback runs:
        // the bad headers become null while method and body survive.
        val opts = bridge.parseFetchOpts("""{"method":"POST","headers":["bad"],"body":"data"}""")

        Triple(opts.method, opts.headers, opts.body) shouldBe Triple("POST", null, "data")
    }

    /**
     * Resolving the User-Agent loads the WebView provider, which blocks for seconds on a cold device.
     * The host builds this bridge while holding a Metro singleton lock, so doing it at construction
     * froze every screen waiting on that lock. Reading it per request is what keeps that off the
     * construction path.
     */
    @Test
    fun `constructing the bridge never resolves the user agent`() {
        var resolved = 0

        LnHostBridge(preferenceStore = mockk(relaxed = true), client = mockk(relaxed = true)) {
            resolved++
            ""
        }

        resolved shouldBe 0
    }

    @Test
    fun `an error status is reported as the failure the plugin's call carries`() {
        var failure: Throwable? = null

        answering(521).runFetch("https://example.org/browse", "{}") { failure = it }

        failure.shouldBeInstanceOf<HttpException>().code shouldBe 521
    }

    @Test
    fun `an answered request reports no failure`() {
        var failure: Throwable? = null

        answering(200).runFetch("https://example.org/browse", "{}") { failure = it }

        failure shouldBe null
    }

    // OkHttp replaces a request's Cookie header with the jar's whenever the jar holds any, so a cookie
    // a plugin sets itself (an age gate, a read token) never reached a site that had set one of its own.
    @Test
    fun `a cookie the plugin sets reaches the site beside the ones it stored`() {
        cookieSeen("""{"headers":{"Cookie":"fr_read=token"}}""") shouldBe "session=stored; fr_read=token"
    }

    @Test
    fun `a cookie the plugin sets wins over a stored one of the same name`() {
        cookieSeen("""{"headers":{"Cookie":"session=mine"}}""") shouldBe "session=mine"
    }

    // The Cookie header a local server receives for one fetch, with the jar holding `session=stored`.
    private fun cookieSeen(optsJson: String): String? {
        val seen = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                seen.set(exchange.requestHeaders.getFirst("Cookie"))
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            val stored = object : CookieJar {
                override fun loadForRequest(url: HttpUrl) =
                    listOf(Cookie.Builder().name("session").value("stored").domain(url.host).build())

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            }
            LnHostBridge(mockk(relaxed = true), OkHttpClient.Builder().cookieJar(stored).build()) { "agent" }
                .runFetch("http://127.0.0.1:${server.address.port}/chapter", optsJson) {}
        } finally {
            server.stop(0)
        }
        return seen.get()
    }

    // Answers every request with [code] itself, so no request reaches the network.
    private fun answering(code: Int) = LnHostBridge(
        preferenceStore = mockk(relaxed = true),
        client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body("<html></html>".toResponseBody()).build()
        }.build(),
    ) { "agent" }
}
