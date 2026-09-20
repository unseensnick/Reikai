package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The settings Test button names the failure in the reader's language, so the status the server (or
 * a proxy in front of it) answered with has to reach the screen as a case rather than a sentence.
 */
class FlareSolverrTestFailureTest {

    @Test
    fun `a password challenge is told apart from any other refusal`() {
        FlareSolverrTestFailure.ofStatus(401) shouldBe FlareSolverrTestFailure.AUTH_REQUIRED
    }

    @Test
    fun `a proxy's own password challenge counts as one too`() {
        FlareSolverrTestFailure.ofStatus(407) shouldBe FlareSolverrTestFailure.AUTH_REQUIRED
    }

    @Test
    fun `a flat refusal is not read as a password prompt`() {
        FlareSolverrTestFailure.ofStatus(403) shouldBe FlareSolverrTestFailure.FORBIDDEN
    }

    @Test
    fun `nothing at the address has its own case`() {
        FlareSolverrTestFailure.ofStatus(404) shouldBe FlareSolverrTestFailure.NOT_FOUND
    }

    @Test
    fun `any other status falls back to the generic server error`() {
        FlareSolverrTestFailure.ofStatus(500) shouldBe FlareSolverrTestFailure.HTTP_ERROR
    }
}
