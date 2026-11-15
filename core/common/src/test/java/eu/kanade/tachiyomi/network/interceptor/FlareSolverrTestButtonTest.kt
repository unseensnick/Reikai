package eu.kanade.tachiyomi.network.interceptor

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.net.InetSocketAddress

/** [FlareSolverrClient.test], the settings Test row, against a stand-in server on the loopback address. */
class FlareSolverrTestButtonTest {

    @Volatile
    private var reply: Pair<Int, String> = 200 to "{}"

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val (code, body) = reply
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val client = FlareSolverrClient(mockk(relaxed = true), NetworkPreferences(InMemoryPreferenceStore(), false))

    private val address = "http://127.0.0.1:${server.address.port}"

    @AfterEach
    fun stop() = server.stop(0)

    private fun reasonFor(code: Int, body: String): FlareSolverrTestFailure? {
        reply = code to body
        var result: FlareSolverrTestResult? = null
        runTest { result = client.test(address) }
        return (result as? FlareSolverrTestResult.Failure)?.reason
    }

    @Test
    fun `a solve the server reports failed reads as a failed solve`() {
        reasonFor(500, """{"status": "error", "message": "Challenge not solved"}""") shouldBe
            FlareSolverrTestFailure.SOLVE_FAILED
    }

    @Test
    fun `a solver's own 404 for a path it does not serve reads as nothing at the address`() {
        // Byparr is a FastAPI app, which answers an unknown path with {"detail": "Not Found"}.
        reasonFor(404, """{"detail": "Not Found"}""") shouldBe FlareSolverrTestFailure.NOT_FOUND
    }

    @Test
    fun `a passed solve of the test page succeeds`() {
        reply = 200 to """{"status": "ok", "solution": {"status": 200}}"""
        var result: FlareSolverrTestResult? = null

        runTest { result = client.test(address) }

        result shouldBe FlareSolverrTestResult.Success
    }
}
