package reikai.novel.source.ireader

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * A site's search form often answers its POST with a 302 to the results page. Ktor's OkHttp engine turns
 * OkHttp's redirects off and follows only GET and HEAD itself, so an extension used to get the bare 302
 * back and list nothing. Over a real socket, since OkHttp follows redirects after the app's interceptors.
 */
class IReaderRedirectTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/search") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Location", "result/?searchid=1")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        createContext("/result/") { exchange ->
            val body = "${exchange.requestMethod} results".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val clients = IReaderHttpClients(mockk(), OkHttpClient()) { "app-agent" }

    @AfterEach
    fun tearDown() = server.stop(0)

    @Test
    fun `a form POST answered with a redirect reaches the results page, as a GET`() = runTest {
        val response = clients.default.submitForm(
            url = "http://127.0.0.1:${server.address.port}/search",
            formParameters = parameters { append("keyboard", "chaotic") },
        )

        response.bodyAsText() shouldBe "GET results"
    }
}
