package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter

class NovelMissingChaptersTest {

    private var nextId = 1L

    private fun chapter(number: Double): NovelChapter =
        NovelChapter(
            id = nextId++,
            novelId = 1L,
            url = "/$number/$nextId",
            name = "Chapter $number",
            read = false,
            bookmark = false,
            lastTextProgress = 0,
            chapterNumber = number,
            sourceOrder = nextId,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )

    private fun List<NovelChapterListEntry>.numbers(): List<Double> =
        filterIsInstance<NovelChapterListEntry.Item>().map { it.chapter.chapterNumber }

    private fun List<NovelChapterListEntry>.missing(): List<Int> =
        filterIsInstance<NovelChapterListEntry.Missing>().map { it.count }

    @Test
    fun `inserts a missing separator before the chapter that opens the gap`() {
        val entries = buildNovelChapterListEntries(
            chapters = listOf(chapter(1.0), chapter(2.0), chapter(4.0)),
            sortDescending = false,
        )

        // Item(1), Item(2), Missing(1), Item(4): the gap sits directly before chapter 4.
        entries.map {
            when (it) {
                is NovelChapterListEntry.Item -> it.chapter.chapterNumber
                is NovelChapterListEntry.Missing -> "missing-${it.count}"
            }
        } shouldBe listOf(1.0, 2.0, "missing-1", 4.0)
    }

    @Test
    fun `consecutive chapters produce no separators`() {
        val entries = buildNovelChapterListEntries(
            chapters = listOf(chapter(1.0), chapter(2.0), chapter(3.0)),
            sortDescending = false,
        )

        entries.missing() shouldBe emptyList()
    }

    @Test
    fun `ascending list emits a leading gap before the first chapter`() {
        val entries = buildNovelChapterListEntries(
            chapters = listOf(chapter(3.0), chapter(4.0)),
            sortDescending = false,
        )

        entries.first() shouldBe NovelChapterListEntry.Missing(id = "null-1", count = 2)
    }

    @Test
    fun `descending list emits a trailing gap after the last chapter`() {
        val entries = buildNovelChapterListEntries(
            chapters = listOf(chapter(4.0), chapter(3.0)),
            sortDescending = true,
        )

        entries.last() shouldBe NovelChapterListEntry.Missing(id = "null-2", count = 2)
    }

    @Test
    fun `an unrecognized chapter number yields no separator`() {
        val entries = buildNovelChapterListEntries(
            chapters = listOf(chapter(-1.0), chapter(1.0)),
            sortDescending = false,
        )

        entries.numbers() shouldBe listOf(-1.0, 1.0)
        entries.missing() shouldBe emptyList()
    }
}
