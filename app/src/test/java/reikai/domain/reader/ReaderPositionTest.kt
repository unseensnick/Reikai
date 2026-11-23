package reikai.domain.reader

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * The reader's position rules, pinned once over both media rather than as a twin pair. The completion
 * rule is the one that matters most: it used to live in the manga view model as an index comparison,
 * which a continuous medium can never satisfy, so a novel would have read to the end of a chapter and
 * never been credited with it.
 */
class ReaderPositionTest {

    // A paged chapter finishes on its last page; a continuous one at the incumbent 97%, which is the
    // threshold novels already ship with.
    @ParameterizedTest(name = "{0}")
    @MethodSource("completions")
    fun `a chapter is complete on its last page or past the continuous threshold`(
        name: String,
        progress: ChapterProgress,
        expected: Boolean,
    ) {
        progress.isChapterComplete shouldBe expected
    }

    @ParameterizedTest(name = "{1} hundredths against a {2}% threshold is complete: {3}")
    @CsvSource(
        "a continuous chapter reaches a threshold the reader lowered, 6000, 60, true",
        "a continuous chapter short of a threshold the reader raised is not complete, 9900, 100, false",
    )
    fun `a continuous chapter completes at the threshold the reader set`(
        name: String,
        hundredths: Long,
        continuousPercent: Int,
        expected: Boolean,
    ) {
        ChapterProgress.Percent(hundredths).isChapterComplete(continuousPercent) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fractions")
    fun `the navigator's thumb sits at the fraction read`(name: String, progress: ChapterProgress, expected: Float) {
        progress.fraction shouldBe (expected plusOrMinus 0.0001f)
    }

    // The count feeds Material's steps, which throws below zero, so the arithmetic that used to
    // produce a negative one is clamped here instead of guarded at the call site.
    @ParameterizedTest(name = "{0}")
    @MethodSource("slider")
    fun `the slider offers detents and scrubbing only where there is a span`(
        name: String,
        progress: ChapterProgress,
        steps: Int,
        isSeekable: Boolean,
    ) {
        progress.stepCount shouldBe steps
        progress.isSeekable shouldBe isSeekable
    }

    // A page is stored zero-based and reads one-based, the same rule the recents rows use.
    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `the navigator labels each end in the medium's own unit`(
        name: String,
        progress: ChapterProgress,
        leading: String,
        trailing: String,
        pageIndex: Int?,
    ) {
        progress.leadingLabel shouldBe leading
        progress.trailingLabel shouldBe trailing
        progress.pageIndex shouldBe pageIndex
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("seeks")
    fun `a scrub lands within the chapter in the medium's own unit`(
        name: String,
        from: ChapterProgress,
        fraction: Float,
        expected: ChapterProgress,
    ) {
        from.seekTo(fraction) shouldBe expected
    }

    @Test
    fun `a whole percent the reader reports is stored as hundredths`() {
        ChapterProgress.Percent.ofWhole(42) shouldBe ChapterProgress.Percent(hundredths = 4200)
    }

    @Test
    fun `a stored position reads as the whole percent below it`() {
        ChapterProgress.Percent(hundredths = 4299).wholePercent shouldBe 42L
    }

    // Both novel renderers report a scroll fraction through this one rounding, which decides when the
    // completion threshold is reached and where a reopen lands.
    @ParameterizedTest(name = "{0}")
    @MethodSource("scrollFractions")
    fun `a scroll fraction reports the hundredth below it, within the chapter`(
        name: String,
        fraction: Double,
        expected: Long,
    ) {
        ChapterProgress.Percent.of(fraction) shouldBe ChapterProgress.Percent(expected)
    }

    companion object {
        private fun pages(lastPageRead: Int, pageCount: Int) = ChapterProgress.Pages(lastPageRead, pageCount)

        private fun percent(hundredths: Long) = ChapterProgress.Percent(hundredths)

        @JvmStatic
        fun completions() = listOf(
            Arguments.of("a paged chapter open on its last page is complete", pages(37, 38), true),
            Arguments.of("a paged chapter open short of the end is not complete", pages(36, 38), false),
            // Incumbent manga behaviour, kept deliberately: one page read is the whole chapter read.
            // The hazard this used to carry was a continuous chapter modelled as one stub page,
            // which the Percent variant now makes unrepresentable.
            Arguments.of("a one-page chapter is complete on its only page", pages(0, 1), true),
            Arguments.of("a chapter whose length is unknown never completes", pages(0, 0), false),
            Arguments.of("a continuous chapter at the threshold is complete", percent(9700), true),
            Arguments.of("a continuous chapter just under the threshold is not complete", percent(9699), false),
            Arguments.of("a continuous chapter is not complete the moment it opens", percent(0), false),
        )

        @JvmStatic
        fun fractions() = listOf(
            Arguments.of("a paged chapter at its first page reads as no progress", pages(0, 38), 0f),
            Arguments.of("a paged chapter at its last page reads as fully through", pages(37, 38), 1f),
            Arguments.of("a one-page chapter has no span to be part-way through", pages(0, 1), 0f),
            Arguments.of("a continuous chapter reads its stored hundredths as a fraction", percent(6200), 0.62f),
            Arguments.of("a continuous chapter at the end reads as fully through", percent(10_000), 1f),
        )

        @JvmStatic
        fun slider() = listOf(
            Arguments.of("a paged chapter offers a detent for every page between the ends", pages(0, 38), 36, true),
            Arguments.of("a one-page chapter offers no detents and nowhere to scrub", pages(0, 1), 0, false),
            Arguments.of(
                "a chapter of unknown length offers no detents rather than a negative count",
                pages(0, 0),
                0,
                false,
            ),
            // The detents are the marks that read as a progress rail; the novel reader has always had them.
            Arguments.of("a continuous chapter keeps the rail's detents and always scrubs", percent(0), 33, true),
        )

        @JvmStatic
        fun labels() = listOf(
            Arguments.of("a paged chapter reads its page one-based and names its length", pages(0, 38), "1", "38", 0),
            Arguments.of("a paged chapter names the page it sits on", pages(5, 38), "6", "38", 5),
            Arguments.of(
                "a continuous chapter reads as whole percents and names no page",
                percent(6250),
                "62%",
                "100%",
                null,
            ),
        )

        @JvmStatic
        fun seeks() = listOf(
            Arguments.of("a paged chapter scrubbed to the middle lands on a page", pages(0, 11), 0.5f, pages(5, 11)),
            Arguments.of(
                "a paged chapter scrubbed past the end stops at the last page",
                pages(0, 11),
                2f,
                pages(10, 11),
            ),
            Arguments.of(
                "a paged chapter scrubbed before the start stops at the first page",
                pages(5, 11),
                -1f,
                pages(0, 11),
            ),
            Arguments.of("a continuous chapter lands on the stored hundredths", percent(0), 0.62f, percent(6200)),
            Arguments.of(
                "a continuous chapter scrubbed past the end stops at its end",
                percent(0),
                2f,
                percent(10_000),
            ),
            Arguments.of(
                "a continuous chapter scrubbed before the start stops at its start",
                percent(6200),
                -1f,
                percent(0),
            ),
        )

        @JvmStatic
        fun scrollFractions() = listOf(
            Arguments.of("a scroll fraction reports the hundredth below it", 0.96669, 9666L),
            // A paged share goes out to the page and comes back as a double, which can land a hair under it.
            Arguments.of("a hundredth echoed back through a double reads as itself", 1234.0 / 10_000 - 1e-13, 1234L),
            Arguments.of("a scroll fraction before the start reports the start", -0.2, 0L),
            Arguments.of("a scroll fraction past the end reports the end", 1.3, ChapterProgress.Percent.FULL),
        )
    }
}
