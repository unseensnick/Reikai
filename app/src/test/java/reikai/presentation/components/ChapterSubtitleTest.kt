package reikai.presentation.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The line under a chapter row: its source in a merged group, then its scanlator (a novel's translation group),
 * for both content types alike, so neither adapter composes it on its own.
 */
class ChapterSubtitleTest {

    @ParameterizedTest(name = "{0} and {1} read {2}")
    @MethodSource("subtitles")
    fun `the line names the source before the scanlator, leaving out what is missing`(
        source: String?,
        scanlator: String?,
        line: String?,
    ) {
        chapterSubtitle(source, scanlator) shouldBe line
    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("parts")
    fun `a blank piece is nothing, so the row draws no separator for it`(value: String?, part: String?) {
        subtitlePart(value) shouldBe part
    }

    @ParameterizedTest(name = "{0} labels {1}")
    @MethodSource("groups")
    fun `only a merged group's members are labelled with their source`(
        members: Map<Long, String>,
        labels: Map<Long, String>,
    ) {
        mergeSourceLabels(members) shouldBe labels
    }

    companion object {
        @JvmStatic
        fun subtitles() = listOf(
            Arguments.of("MangaDex", "Some Group", "MangaDex • Some Group"),
            Arguments.of("NovelUpdates", null, "NovelUpdates"),
            Arguments.of(null, "Some Group", "Some Group"),
            Arguments.of(null, null, null),
            // Through subtitlePart, whose own cases are below.
            Arguments.of("MangaDex", "   ", "MangaDex"),
        )

        @JvmStatic
        fun parts() = listOf(
            Arguments.of("Some Group", "Some Group"),
            Arguments.of("   ", null),
            Arguments.of(null, null),
        )

        @JvmStatic
        fun groups() = listOf(
            Arguments.of(mapOf(1L to "A", 2L to "B"), mapOf(1L to "A", 2L to "B")),
            // Every row would carry the same name.
            Arguments.of(mapOf(1L to "A"), emptyMap<Long, String>()),
        )
    }
}
