package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Runs through [addUserAgentInterceptors], so it fails if the shared client puts the default one first. */
class MangaDexHeadersInterceptorTest {

    private val appUserAgent = MangaDexHeadersInterceptor.defaultUserAgent()
    private val browserUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/141.0.0.0 Mobile"

    private fun sentUserAgent(request: Request): String? = sent(request).header("User-Agent")

    private fun sentReferer(url: String, referer: String): String? =
        sent(Request.Builder().url(url).header("Referer", referer).build()).header("Referer")

    private fun sent(request: Request): Request {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addUserAgentInterceptors { browserUserAgent }
            .addInterceptor(
                Interceptor { chain ->
                    sent = chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody())
                        .build()
                },
            )
            .build()
        client.newCall(request).execute().close()
        return sent!!
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://uploads.mangadex.org/covers/a/b.jpg",
            "https://api.mangadex.org/manga",
            "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/logout",
            "https://mangadex.org/title/a",
            "https://abc123.mangadex.network/data/hash/1.jpg",
        ],
    )
    fun `a MangaDex request without a User-Agent gets the app one`(url: String) {
        sentUserAgent(Request.Builder().url(url).build()) shouldBe appUserAgent
    }

    @Test
    fun `a MangaDex cover carrying a source's browser User-Agent gets the app one`() {
        val request = Request.Builder()
            .url("https://uploads.mangadex.org/covers/a/b.jpg")
            .header("User-Agent", browserUserAgent)
            .build()

        sentUserAgent(request) shouldBe appUserAgent
    }

    @Test
    fun `a MangaDex request keeps a non-browser User-Agent its caller set`() {
        val request = Request.Builder().url("https://api.mangadex.org/manga").header("User-Agent", "Custom/1.0").build()

        sentUserAgent(request) shouldBe "Custom/1.0"
    }

    @Test
    fun `a MangaDex cover drops another site's Referer, which gets the hotlink placeholder`() {
        sentReferer("https://uploads.mangadex.org/covers/a/b.jpg", "https://www.mangakakalot.gg/") shouldBe null
    }

    @Test
    fun `a MangaDex request keeps MangaDex's own Referer`() {
        sentReferer("https://uploads.mangadex.org/covers/a/b.jpg", "https://mangadex.org/") shouldBe
            "https://mangadex.org/"
    }

    @Test
    fun `another host keeps its Referer`() {
        sentReferer("https://example.org/a.jpg", "https://www.mangakakalot.gg/") shouldBe "https://www.mangakakalot.gg/"
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://api.similarmanga.com/similar/a.json",
            "https://notmangadex.org/a",
            "https://mangadex.org.example.com/a",
            "https://notmangadex.network/a",
        ],
    )
    fun `another host still gets the default User-Agent`(url: String) {
        sentUserAgent(Request.Builder().url(url).build()) shouldBe browserUserAgent
    }
}
