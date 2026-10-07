package reikai.presentation.details

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.track.TrackerEntryMissingException
import reikai.data.track.TrackerSignedOutException
import java.net.UnknownHostException

/** "Fill from tracker" words a missing entry its own way; everything else goes through the tracker kernel. */
class TrackerAutofillErrorTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("cases")
    fun `a 404 or an empty answer reads as no entry on the tracker`(error: Throwable, expected: Boolean) {
        isMissingOnTracker(error) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            Arguments.of(HttpException(404), true),
            Arguments.of(TrackerEntryMissingException("Kitsu"), true),
            Arguments.of(HttpException(500), false),
            Arguments.of(TrackerSignedOutException("Kitsu"), false),
            Arguments.of(UnknownHostException("kitsu.app"), false),
        )
    }
}
