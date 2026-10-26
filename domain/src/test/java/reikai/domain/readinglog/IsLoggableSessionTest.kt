package reikai.domain.readinglog

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Instant

/**
 * The bounds a logged session keeps to (statistics.md), which recording and both restorers share. The
 * literals restate the private limits on purpose: a week of time, ending between the epoch and the year 3000.
 */
class IsLoggableSessionTest {

    @ParameterizedTest
    @CsvSource(
        "0, 1, true",
        "1000, 604800000, true",
        "1000, 604800001, false",
        "1000, 0, false",
        "-1, 60000, false",
        "32503680000000, 60000, true",
        "32503680000001, 60000, false",
    )
    fun `a session is kept only inside the log's bounds`(endedAt: Long, duration: Long, kept: Boolean) {
        isLoggableSession(Instant.fromEpochMilliseconds(endedAt), duration) shouldBe kept
    }
}
