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
            dateFetch = 0L, dateUpload = 0L, page = "",
        )

    // The rows as the chapter list shows them, sourceOrder deliberately disagreeing with that order:
    // the caller hands over the shown list, so unread runs [12, 10, 13] here and source order would not.
    private val chapters = listOf(
        chapter(id = 11, order = 0, read = true, bookmark = true),
        chapter(id = 12, order = 1),
        chapter(id = 14, order = 3, read = true),
        chapter(id = 10, order = 2),
        chapter(id = 13, order = 4, bookmark = true),
    )

    private fun select(
        action: DownloadAction,
        excluded: Set<Long> = emptySet(),
        readElsewhere: Set<Long> = emptySet(),
        bookmarkedElsewhere: Set<Long> = emptySet(),
        from: List<NovelChapter> = chapters,
        sortDescending: Boolean = false,
    ): List<Long> =
        selectChaptersForDownloadAction(from, sortDescending, action, excluded, readElsewhere, bookmarkedElsewhere)
            .map { it.id }

    @Test
    fun `next 1 takes the first unread chapter of the list as shown`() {
        select(DownloadAction.NEXT_1_CHAPTER) shouldBe listOf(12L)
    }

    @Test
    fun `next N counts only unread and stops when fewer remain`() {
        select(DownloadAction.NEXT_5_CHAPTERS) shouldBe listOf(12L, 10L, 13L)
    }

    @Test
    fun `a newest-first list is queued from its end, the chapter the reader reaches next`() {
        select(DownloadAction.NEXT_1_CHAPTER, sortDescending = true) shouldBe listOf(13L)
    }

    @Test
    fun `unread returns every unread chapter in reading order`() {
        select(DownloadAction.UNREAD_CHAPTERS) shouldBe listOf(12L, 10L, 13L)
    }

    @Test
    fun `bookmarked returns bookmarked chapters regardless of read state`() {
        select(DownloadAction.BOOKMARKED_CHAPTERS) shouldBe listOf(11L, 13L)
    }

    @Test
    fun `next 1 skips an excluded leading chapter`() {
        // 12 is the first unread; excluding it (already downloaded or queued) advances to 10.
        select(DownloadAction.NEXT_1_CHAPTER, excluded = setOf(12L)) shouldBe listOf(10L)
    }

    @Test
    fun `next N excludes queued or downloaded before take, so it still fills a full batch`() {
        // 10 unread chapters, the first 3 already queued or downloaded: next-5 must return 5 FRESH
        // chapters (4..8), not stop short at 2 because the take happened before the exclusion.
        val many = (1L..10L).map { chapter(id = it, order = it) }
        select(DownloadAction.NEXT_5_CHAPTERS, excluded = setOf(1L, 2L, 3L), from = many) shouldBe
            listOf(4L, 5L, 6L, 7L, 8L)
    }

    @Test
    fun `unread excludes excluded ids`() {
        select(DownloadAction.UNREAD_CHAPTERS, excluded = setOf(10L)) shouldBe listOf(12L, 13L)
    }

    @Test
    fun `bookmarked excludes excluded ids`() {
        select(DownloadAction.BOOKMARKED_CHAPTERS, excluded = setOf(13L)) shouldBe listOf(11L)
    }

    @Test
    fun `a chapter read on another grouped source is not offered as next unread`() {
        select(DownloadAction.NEXT_5_CHAPTERS, readElsewhere = setOf(12L)) shouldBe listOf(10L, 13L)
    }

    @Test
    fun `a chapter bookmarked on another grouped source is offered as bookmarked`() {
        select(DownloadAction.BOOKMARKED_CHAPTERS, bookmarkedElsewhere = setOf(10L)) shouldBe
            listOf(11L, 10L, 13L)
    }
}
