package reikai.domain.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

class MergedHistoryTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `an incoming history row merges into the stored one`(
        case: String,
        readAt: Instant?,
        readDuration: Long,
        storedReadAt: Instant?,
        storedDuration: Long,
        expected: Pair<Instant?, Long>,
    ) {
        mergedHistory(readAt, readDuration, storedReadAt, storedDuration) shouldBe expected
    }

    private companion object {
        fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)

        @JvmStatic
        fun cases() = listOf(
            Arguments.of(
                "a chapter with no stored row takes the incoming read and time",
                at(500),
                60L,
                null,
                0L,
                at(500) to 60L,
            ),
            Arguments.of(
                "a row already holding the incoming read gains no time",
                at(500),
                60L,
                at(500),
                60L,
                at(500) to 0L,
            ),
            Arguments.of(
                "a later stored read is kept while the longer incoming time tops the row up",
                at(500),
                60L,
                at(900),
                20L,
                at(900) to 40L,
            ),
            Arguments.of(
                "a later incoming read moves the row forward without adding time it already has",
                at(900),
                20L,
                at(500),
                60L,
                at(900) to 0L,
            ),
            Arguments.of("a removed incoming read keeps the stored time", null, 5L, at(500), 5L, at(500) to 0L),
            Arguments.of("a row removed on both sides stays removed", null, 5L, null, 5L, null to 0L),
        )
    }
}
