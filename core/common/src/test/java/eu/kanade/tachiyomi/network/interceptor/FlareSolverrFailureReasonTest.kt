package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Why a solver says a command failed, read from the bodies the servers really send. */
class FlareSolverrFailureReasonTest {

    @Test
    fun `a FlareSolverr-shaped error gives its message`() {
        // Solverr 1.9.2's answer, HTTP 500, for a host that does not resolve.
        val body = """{"status": "error", "message": "Error: Error solving the challenge. Page.goto: """ +
            """NS_ERROR_UNKNOWN_HOST", "startTimestamp": 1, "endTimestamp": 2, "version": "1.9.2"}"""

        flareSolverrFailureReason(body) shouldBe "Error: Error solving the challenge. Page.goto: NS_ERROR_UNKNOWN_HOST"
    }

    @Test
    fun `a Byparr error gives its detail`() {
        flareSolverrFailureReason("""{"detail": "Timed out while solving the challenge"}""") shouldBe
            "Timed out while solving the challenge"
    }

    @Test
    fun `a Byparr validation error gives each message`() {
        val body = """{"detail": [{"loc": ["body", "url"], "msg": "String should match pattern", "type": "x"}]}"""

        flareSolverrFailureReason(body) shouldBe "String should match pattern"
    }

    @Test
    fun `a proxy's error page gives no reason`() {
        flareSolverrFailureReason("<html><body>502 Bad Gateway</body></html>") shouldBe null
    }

    @Test
    fun `a message without the solver's error status gives no reason`() {
        // A proxy can answer in JSON too; only the solver's own shape says the solve failed.
        flareSolverrFailureReason("""{"message": "Unauthorized"}""") shouldBe null
    }
}
