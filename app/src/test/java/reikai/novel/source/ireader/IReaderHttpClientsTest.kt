package reikai.novel.source.ireader

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import reikai.novel.source.FetchFailures
import java.io.IOException

class IReaderHttpClientsTest {

    private class Book(val name: String)

    private var sent: Request? = null

    // Answers every request itself, so the client under test reaches no network.
    private val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
        sent = chain.request()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "application/json")
            .body("""{"name":"Beware of the Bald Guy"}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val clients = IReaderHttpClients(mockk(), okHttp) { "app-agent" }

    @Test
    fun `a request that names no agent goes out as the app`() = runTest {
        clients.default.get("https://example.org/novel")

        sent!!.header(HttpHeaders.UserAgent) shouldBe "app-agent"
    }

    @Test
    fun `an extension's own agent is kept`() = runTest {
        clients.default.get("https://example.org/novel") { header(HttpHeaders.UserAgent, "extension-agent") }

        sent!!.header(HttpHeaders.UserAgent) shouldBe "extension-agent"
    }

    @Test
    fun `the cloudflare client also goes out as the app`() = runTest {
        clients.cloudflareClient.get("https://example.org/novel")

        sent!!.header(HttpHeaders.UserAgent) shouldBe "app-agent"
    }

    @Test
    fun `a JSON body decodes into an extension's plain class, as IReader's Gson client does`() = runTest {
        clients.default.get("https://example.org/novel").body<Book>().name shouldBe "Beware of the Bald Guy"
    }

    @Test
    fun `a Madara chapter list the site refuses reaches the source from the novel's own address`() = runTest {
        // A current Madara site: the theme's old ask refused, the novel's own chapter address serving.
        val site = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (answered, code, body) = when {
                request.url.encodedPath.endsWith("admin-ajax.php") -> Triple(request, 400, "0")
                request.url.queryParameter("p") == "42" ->
                    Triple(request.newBuilder().url("https://example.org/novel/a/").build(), 200, "")
                else -> Triple(request, 200, CHAPTER_LIST)
            }
            Response.Builder().request(answered).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody()).build()
        }.build()

        val response = IReaderHttpClients(mockk(), site) { "app-agent" }.default.submitForm(
            url = "https://example.org/wp-admin/admin-ajax.php",
            formParameters = Parameters.build {
                append("action", "manga_get_chapters")
                append("manga", "42")
            },
        )

        response.bodyAsText() shouldBe CHAPTER_LIST
    }

    @Test
    fun `an error page an extension is handed is reported to the source call`() = runTest {
        val failures = FetchFailures()

        withContext(failures) { answering(521).default.get("https://example.org/novel") }

        failures.last.shouldBeInstanceOf<HttpException>().code shouldBe 521
    }

    @Test
    fun `the cloudflare client reports an error page too`() = runTest {
        val failures = FetchFailures()

        withContext(failures) { answering(503).cloudflareClient.get("https://example.org/novel") }

        failures.last.shouldBeInstanceOf<HttpException>().code shouldBe 503
    }

    /** An extension that catches the failure itself hands back an empty page, which the call then explains. */
    @Test
    fun `a request that never reaches the site is reported to the source call`() = runTest {
        val failures = FetchFailures()
        val unreachable = IReaderHttpClients(
            mockk(),
            OkHttpClient.Builder().addInterceptor { throw IOException("no connection") }.build(),
        ) { "app-agent" }

        runCatching { withContext(failures) { unreachable.default.get("https://example.org/novel") } }

        failures.last.shouldBeInstanceOf<IOException>().message shouldBe "no connection"
    }

    @Test
    fun `an answered request reports no failure`() = runTest {
        val failures = FetchFailures()

        withContext(failures) { answering(200).default.get("https://example.org/novel") }

        failures.last shouldBe null
    }

    private fun answering(code: Int) = IReaderHttpClients(
        mockk(),
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body("<html></html>".toResponseBody()).build()
        }.build(),
    ) { "app-agent" }

    private companion object {
        const val CHAPTER_LIST = """<li class="wp-manga-chapter"><a href="x">Chapter 1</a></li>"""
    }
}
