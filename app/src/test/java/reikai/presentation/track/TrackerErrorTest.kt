package reikai.presentation.track

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TrackerErrorTest {

    @ParameterizedTest(name = "{0}, online {1} -> {2}")
    @MethodSource("cases")
    fun `a failed tracker call is told to the user by what went wrong`(
        error: Throwable,
        isOnline: Boolean,
        expected: TrackerError,
    ) {
        TrackerError.of(error, isOnline) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of(UnknownHostException("api.example"), false, TrackerError.Offline),
            Arguments.of(UnknownHostException("api.example"), true, TrackerError.Unreachable),
            Arguments.of(IOException("wrapped", UnknownHostException("api.example")), false, TrackerError.Offline),
            Arguments.of(ConnectException("refused"), false, TrackerError.Offline),
            Arguments.of(SocketTimeoutException("timeout"), true, TrackerError.Unreachable),
            Arguments.of(HttpException(401), true, TrackerError.SignedOut),
            Arguments.of(HttpException(403), true, TrackerError.SignedOut),
            Arguments.of(HttpException(500), true, TrackerError.Http(500)),
            Arguments.of(
                IOException("Not authenticated with NovelList"),
                true,
                TrackerError.Other("Not authenticated with NovelList"),
            ),
        )
    }
}
