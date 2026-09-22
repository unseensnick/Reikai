package reikai.novel.source.ireader

import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

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
}
