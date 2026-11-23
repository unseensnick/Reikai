package reikai.data.notification

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * The one rule both update notifiers name their chapters by. Manga carried it alone until the novel
 * updater stopped reporting a bare count, so these cases hold for both content types.
 */
class NewChaptersTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("chapters")
    fun `new chapters are named by number in reading order`(
        name: String,
        chapterNumbers: List<Double>,
        total: Int,
        expected: NewChapters,
    ) {
        newChapters(chapterNumbers, total) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("summaries")
    fun `a summary names the shown entries`(
        name: String,
        titles: List<String?>,
        hideAll: Boolean,
        expected: NewChaptersSummary,
    ) {
        newChaptersSummary(titles, hideAll) shouldBe expected
    }

    @ParameterizedTest(name = "{0} new chapters offer a download: {1}")
    @CsvSource("15, true", "16, false")
    fun `a download is offered up to fifteen new chapters`(newChapters: Int, expected: Boolean) {
        offersDownloadAction(newChapters) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun chapters() = listOf(
            Arguments.of(
                "a source that numbered nothing falls back to a count",
                listOf(-1.0, -1.0),
                2,
                NewChapters.Count(2),
            ),
            Arguments.of(
                "one numbered chapter is named on its own",
                listOf(3.0),
                1,
                NewChapters.Single("3", remaining = 0),
            ),
            Arguments.of(
                "the unnumbered chapters alongside a named one are counted as remaining",
                listOf(3.0, -1.0, -1.0),
                3,
                NewChapters.Single("3", remaining = 2),
            ),
            Arguments.of(
                "several chapters are listed in reading order, not the order they arrived",
                listOf(3.0, 1.0, 2.0),
                3,
                NewChapters.Multiple(listOf("1", "2", "3"), remaining = 0),
            ),
            Arguments.of(
                "a fractional chapter keeps its point",
                listOf(2.5, 1.0),
                2,
                NewChapters.Multiple(listOf("1", "2.5"), remaining = 0),
            ),
            Arguments.of(
                "past five chapters the rest become a remainder",
                (1..8).map { it.toDouble() },
                8,
                NewChapters.Multiple(listOf("1", "2", "3", "4", "5"), remaining = 3),
            ),
            // Two sources of a merged entry can both report chapter 1; the row must not say "1, 1".
            Arguments.of(
                "a number reported twice is named once",
                listOf(1.0, 1.0, 2.0),
                3,
                NewChapters.Multiple(listOf("1", "2"), remaining = 0),
            ),
            Arguments.of(
                "unnumbered chapters beside several named ones are counted as remaining",
                listOf(1.0, 2.0, -1.0, -1.0),
                4,
                NewChapters.Multiple(listOf("1", "2"), remaining = 2),
            ),
            Arguments.of(
                "a single number reported twice is not counted as one more",
                listOf(1.0, 1.0),
                2,
                NewChapters.Single("1", remaining = 0),
            ),
        )

        @JvmStatic
        fun summaries() = listOf(
            Arguments.of("one shown entry is named", listOf("Berserk"), false, NewChaptersSummary.One("Berserk")),
            Arguments.of(
                "one hidden entry is counted without naming it",
                listOf(null),
                false,
                NewChaptersSummary.Many(1, listOf(null)),
            ),
            Arguments.of(
                "every entry is listed, a hidden one generically",
                listOf("Berserk", null),
                false,
                NewChaptersSummary.Many(2, listOf("Berserk", null)),
            ),
            Arguments.of(
                "with all content hidden no titles are listed",
                listOf(null, null),
                true,
                NewChaptersSummary.Many(2, emptyList()),
            ),
            Arguments.of(
                "a long title is chopped",
                listOf("x".repeat(60)),
                false,
                NewChaptersSummary.One("x".repeat(NOTIF_TITLE_MAX_LEN - 1) + "…"),
            ),
        )
    }
}
