package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.novel.model.NovelChapter

/**
 * The kernel's own cases prove the rule. This proves the novel reader's sheet actually goes through
 * it, which is the half a kernel test cannot see: before the two were joined, this row composed its
 * own subtitle and an unresolved source name reached the row as an empty string, where the row draws
 * its separator on a null check and rendered a bullet with nothing after it.
 */
class NovelReaderChapterRowSubtitleTest {

    private fun chapter(id: Long, novelId: Long) = NovelChapter(
        id = id,
        novelId = novelId,
        url = "/$id",
        name = "Chapter $id",
        read = false,
        bookmark = false,
        lastTextProgress = 0,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = 0,
        dateUpload = 0,
        page = "",
    )

    private fun flags(vararg chapters: NovelChapter) = GroupChapterFlags(
        pooled = chapters.toList(),
        shown = chapters.toList(),
        stitch = chapters.mapIndexed { index, c -> ChapterUnit(c.id, index, 0) },
        id = { it.id },
        read = { it.read },
        bookmark = { it.bookmark },
    ) { emptySet() }

    @Test
    fun `a merged novel's row names the source its chapter came from`() {
        val c = chapter(id = 1, novelId = 10)

        val row = c.toReaderChapterRow(mapOf(10L to "NovelUpdates"), emptyMap(), flags(c))

        row.subtitle shouldBe "NovelUpdates"
    }

    @Test
    fun `an unresolved source name leaves no subtitle rather than a bare separator`() {
        val c = chapter(id = 1, novelId = 10)

        val row = c.toReaderChapterRow(mapOf(10L to ""), emptyMap(), flags(c))

        row.subtitle shouldBe null
    }

    @Test
    fun `an unmerged novel's row has no subtitle`() {
        val c = chapter(id = 1, novelId = 10)

        val row = c.toReaderChapterRow(emptyMap(), emptyMap(), flags(c))

        row.subtitle shouldBe null
    }
}
