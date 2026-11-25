package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter
import kotlin.time.Instant

/** The novel list's wiring onto ChapterGap, whose own test owns the gap rules: where a marker lands and its id. */
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
            dateFetch = Instant.fromEpochMilliseconds(0L),
            dateUpload = null,
            page = "",
        )

    private fun entries(chapters: List<NovelChapter>, sortDescending: Boolean) =
        buildNovelChapterListEntries(chapters, sortDescending, chapters.gapPresent(), paged = false) { false }

    @Test
    fun `inserts a missing separator before the chapter that opens the gap`() {
        val entries = entries(
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
    fun `ascending list emits a leading gap before the first chapter`() {
        val entries = entries(
            chapters = listOf(chapter(3.0), chapter(4.0)),
            sortDescending = false,
        )

        entries.first() shouldBe NovelChapterListEntry.Missing(id = "null-1", count = 2)
    }
}
