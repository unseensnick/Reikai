package reikai.presentation.novel

import eu.kanade.presentation.manga.DownloadAction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter

class NovelDownloadActionsTest {

    private fun chapter(id: Long, order: Long, read: Boolean = false, bookmark: Boolean = false) =
        NovelChapter(
            id = id, novelId = 1L, url = "u$id", name = "c$id", read = read, bookmark = bookmark,
            lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = order,
            dateFetch = 0L, dateUpload = 0L, page = "", isDownloaded = false,
        )

    // sourceOrder deliberately shuffled; unread in source order is [12, 10, 13].
    private val chapters = listOf(
        chapter(id = 13, order = 4, bookmark = true),
        chapter(id = 11, order = 0, read = true, bookmark = true),
        chapter(id = 10, order = 2),
        chapter(id = 14, order = 3, read = true),
        chapter(id = 12, order = 1),
    )

    @Test
    fun `next 1 takes the first unread chapter in source order`() {
        selectChaptersForDownloadAction(chapters, DownloadAction.NEXT_1_CHAPTER).map { it.id } shouldBe listOf(12L)
    }

    @Test
    fun `next N counts only unread and stops when fewer remain`() {
        selectChaptersForDownloadAction(chapters, DownloadAction.NEXT_5_CHAPTERS).map { it.id } shouldBe
            listOf(12L, 10L, 13L)
    }

    @Test
    fun `unread returns every unread chapter in source order`() {
        selectChaptersForDownloadAction(chapters, DownloadAction.UNREAD_CHAPTERS).map { it.id } shouldBe
            listOf(12L, 10L, 13L)
    }

    @Test
    fun `bookmarked returns bookmarked chapters regardless of read state`() {
        selectChaptersForDownloadAction(chapters, DownloadAction.BOOKMARKED_CHAPTERS).map { it.id } shouldBe
            listOf(11L, 13L)
    }
}
