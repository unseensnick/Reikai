package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * The settings Test button names the failure in the reader's language, so the status the server (or
 * a proxy in front of it) answered with has to reach the screen as a case rather than a sentence.
 */
class FlareSolverrTestFailureTest {

    // A gateway status means the proxy is up and the solver is not: a cold solver behind a live proxy
    // answers one for its whole startup, long enough that a reader will press Test during it.
    @ParameterizedTest
    @CsvSource(
        "301, REDIRECTED",
        "401, AUTH_REQUIRED",
        "407, AUTH_REQUIRED",
        "403, FORBIDDEN",
        "404, NOT_FOUND",
        "502, SOLVER_DOWN",
        "503, SOLVER_DOWN",
        "504, SOLVER_DOWN",
        "500, HTTP_ERROR",
    )
    fun `the status a server answered with names its own failure`(status: Int, failure: FlareSolverrTestFailure) {
        FlareSolverrTestFailure.ofStatus(status) shouldBe failure
    }

    @ParameterizedTest
    @MethodSource("exceptions")
    fun `a failed call names its own failure`(
        error: IOException,
        connected: Boolean,
        failure: FlareSolverrTestFailure,
    ) {
        FlareSolverrTestFailure.ofException(error, connected) shouldBe failure
    }

    private companion object {
        // A read or call that ran out after connecting is not an unreachable server; a connect that ran out is.
        @JvmStatic
        fun exceptions() = listOf(
            Arguments.of(SocketTimeoutException("timeout"), true, FlareSolverrTestFailure.TIMED_OUT),
            Arguments.of(InterruptedIOException("timeout"), true, FlareSolverrTestFailure.TIMED_OUT),
            Arguments.of(SocketTimeoutException("failed to connect"), false, FlareSolverrTestFailure.UNREACHABLE),
            Arguments.of(ConnectException("failed to connect"), false, FlareSolverrTestFailure.UNREACHABLE),
            Arguments.of(FlareSolverrLoginRefusedException(), false, FlareSolverrTestFailure.LOGIN_NOT_PRIVATE),
        )
    }
}
