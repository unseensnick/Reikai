package reikai.domain.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class KitsuBackupScoreScaleTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a restored score is put on Kitsu's native scale`(
        case: String,
        marked: Boolean,
        tracks: List<Pair<Long, Double>>,
        tracker: Long,
        score: Double,
        expected: Double,
    ) {
        KitsuBackupScoreScale.of(nativeScaleMarked = marked, tracks).restore(tracker, score) shouldBe expected
    }

    private companion object {
        const val KITSU = 3L
        const val ANILIST = 2L

        @JvmStatic
        fun cases() = listOf(
            Arguments.of(
                "a backup marked as native scale keeps its Kitsu scores",
                true,
                listOf(KITSU to 7.5),
                KITSU,
                7.5,
                7.5,
            ),
            Arguments.of(
                "an unmarked backup's half-step Kitsu score is doubled",
                false,
                listOf(
                    KITSU to 7.5,
                    KITSU to 4.0,
                ),
                KITSU,
                7.5,
                15.0,
            ),
            Arguments.of(
                "an unmarked backup's whole Kitsu score is doubled",
                false,
                listOf(KITSU to 7.5, KITSU to 4.0),
                KITSU,
                4.0,
                8.0,
            ),
            Arguments.of(
                "an unmarked backup with a Kitsu score above 10 is already on the native scale",
                false,
                listOf(KITSU to 4.0, KITSU to 16.0),
                KITSU,
                4.0,
                4.0,
            ),
            // 10 was the top of the old scale, so a backup topping out there is still on it.
            Arguments.of(
                "an unmarked backup whose highest Kitsu score is exactly 10 is doubled",
                false,
                listOf(KITSU to 10.0),
                KITSU,
                10.0,
                20.0,
            ),
            Arguments.of(
                "another tracker's score above 10 says nothing about Kitsu's scale",
                false,
                listOf(
                    KITSU to 4.0,
                    ANILIST to 85.0,
                ),
                KITSU,
                4.0,
                8.0,
            ),
            Arguments.of("an unset Kitsu score stays unset", false, listOf(KITSU to 0.0), KITSU, 0.0, 0.0),
            Arguments.of(
                "an unmarked backup leaves other trackers' scores alone",
                false,
                listOf(
                    KITSU to 4.0,
                    ANILIST to 7.5,
                ),
                ANILIST,
                7.5,
                7.5,
            ),
        )
    }
}
