package reikai.domain.merge

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.model.NovelChapter

/**
 * The stored match key must agree with what the chapter aggregators treat as "the same chapter",
 * otherwise a merged entry's unread badge and its chapter list disagree.
 */
class ChapterMatchKeysTest {

    @Test
    fun `two sources reporting the same chapter share a key`() {
        // One source reports its own 32-bit float, the other's was parsed as a double. They differ
        // by about 2.4e-8, which is exactly what the Float narrowing exists to collapse.
        val fromSource = 1.1f.toDouble()
        val fromRecognition = 1.1

        ChapterMatchKeys.manga(fromSource, isGallerySource = false) shouldBe
            ChapterMatchKeys.manga(fromRecognition, isGallerySource = false)
    }

    @Test
    fun `real sub-chapters stay distinct`() {
        val first = ChapterMatchKeys.manga(10.1, isGallerySource = false)
        val second = ChapterMatchKeys.manga(10.2, isGallerySource = false)

        (first == second) shouldBe false
    }

    @Test
    fun `an unrecognized number has no cross-source identity`() {
        ChapterMatchKeys.manga(-1.0, isGallerySource = false).shouldBeNull()
    }

    @Test
    fun `a gallery source's chapter has no cross-source identity`() {
        // Every gallery source numbers its standalone work 1, so keying by number would collapse
        // two different galleries into one unit.
        ChapterMatchKeys.manga(1.0, isGallerySource = true).shouldBeNull()
    }

    @Test
    fun `the novel key ignores the chapter-number prefix in a title`() {
        val fromOneSource = NovelChapterAggregation.matchKey("Chapter 12: The Duel", 12.0)
        val fromAnother = NovelChapterAggregation.matchKey("12. The Duel", 12.0)

        fromOneSource shouldBe fromAnother
    }

    @Test
    fun `the novel key falls back to the number for a numeric-only title`() {
        NovelChapterAggregation.matchKey("42", 42.0) shouldBe "n:42.0"
    }

    @Test
    fun `a novel chapter with neither a title nor a number has no identity`() {
        NovelChapterAggregation.matchKey("", 0.0).shouldBeNull()
    }

    @Test
    fun `the value overload matches the row overload`() {
        val name = "Chapter 3: Homecoming"
        val number = 3.0

        NovelChapterAggregation.matchKey(name, number) shouldBe
            NovelChapterAggregation.matchKey(
                NovelChapter(
                    id = 1,
                    novelId = 1,
                    url = "url",
                    name = name,
                    read = false,
                    bookmark = false,
                    lastTextProgress = 0,
                    chapterNumber = number,
                    sourceOrder = 0,
                    dateFetch = 0,
                    dateUpload = 0,
                    page = "",
                ),
            )
    }
}
