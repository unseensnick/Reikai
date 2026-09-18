package reikai.presentation.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * One rule, two callers: the manga adapter has a scanlator to add and the novel adapter does not, so
 * the shared row cannot be left to each of them to compose.
 */
class ChapterSubtitleTest {

    @Test
    fun `a merged manga chapter names its source before its scanlator`() {
        chapterSubtitle("MangaDex", "Some Group") shouldBe "MangaDex • Some Group"
    }

    @Test
    fun `a merged novel chapter names its source alone`() {
        chapterSubtitle("NovelUpdates") shouldBe "NovelUpdates"
    }

    @Test
    fun `an unmerged manga chapter still names its scanlator`() {
        chapterSubtitle(null, "Some Group") shouldBe "Some Group"
    }

    @Test
    fun `an unmerged novel chapter says nothing rather than drawing an empty line`() {
        chapterSubtitle(null) shouldBe null
    }

    @Test
    fun `a blank scanlator is not a separator with nothing after it`() {
        chapterSubtitle("MangaDex", "   ") shouldBe "MangaDex"
    }

    @Test
    fun `a blank source does not lead the line`() {
        chapterSubtitle("  ", "Some Group") shouldBe "Some Group"
    }

    @Test
    fun `a piece with text is kept`() {
        subtitlePart("Some Group") shouldBe "Some Group"
    }

    @Test
    fun `a blank piece is nothing, so the row draws no separator for it`() {
        subtitlePart("   ") shouldBe null
    }

    @Test
    fun `an absent piece stays absent`() {
        subtitlePart(null) shouldBe null
    }

    @Test
    fun `every member of a merged group is labelled with its source`() {
        mergeSourceLabels(mapOf(1L to "A", 2L to "B")) shouldBe mapOf(1L to "A", 2L to "B")
    }

    @Test
    fun `a group of one labels nothing, since every row would carry the same name`() {
        mergeSourceLabels(mapOf(1L to "A")) shouldBe emptyMap()
    }
}
