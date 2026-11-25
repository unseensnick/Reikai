package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.CopyToOpen
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.MergeScope
import reikai.domain.novel.interactor.NovelReadingRows
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterListFilters
import reikai.domain.reader.novelChapterListFilters
import kotlin.time.Instant

/** The order the reader and read aloud with no reader both step through, over rows already read. */
class NovelReadingOrderTest {

    private fun chapter(id: Long, number: Double = id.toDouble(), novelId: Long = 1L, read: Boolean = false) =
        NovelChapter(
            id = id,
            novelId = novelId,
            url = "/c/$novelId/$id",
            name = "Chapter $number",
            read = read,
            bookmark = false,
            lastTextProgress = 0L,
            chapterNumber = number,
            sourceOrder = id,
            dateFetch = Instant.fromEpochMilliseconds(0L),
            dateUpload = null,
            page = "",
            scanlator = null,
        )

    private fun order(
        rows: List<NovelChapter>,
        current: Long,
        scope: MergeScope = MergeScope.Source,
        pooled: List<NovelChapter> = emptyList(),
        stitch: List<ChapterUnit> = emptyList(),
        copies: CopyToOpen<NovelChapter>? = null,
        opened: NovelChapter? = null,
        hidden: Set<Long> = emptySet(),
        onDisk: Set<Long> = emptySet(),
        skipRead: Boolean = false,
        skipDuplicates: Boolean = false,
        downloadedOnly: Boolean = false,
        filters: ChapterListFilters? = novelChapterListFilters(0L, 0L, 0L, downloadedOnly = false),
    ) = novelReadingOrder(
        NovelOrderInputs(
            currentId = current,
            scope = scope,
            reading = NovelReadingRows(rows, listOf(1L, 2L), pooled, stitch, copies, DownloadTargets.OWN),
            opened = opened,
            readingOrder = compareBy { it.chapterNumber },
            isHidden = { it.id in hidden },
            markOf = { null },
            owners = emptyMap(),
            onDiskAmong = { chapters, _ -> chapters.mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in onDisk } },
            skipRead = skipRead,
            skipFiltered = false,
            skipDuplicates = skipDuplicates,
            downloadedOnly = downloadedOnly,
            filters = filters,
        ),
    )

    @Test
    fun `chapters are ordered by the reading order`() {
        order(listOf(chapter(3), chapter(1), chapter(2)), current = 1L).orderedIds shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `a hidden chapter is stepped over`() {
        order(listOf(chapter(1), chapter(2), chapter(3)), current = 1L, hidden = setOf(2L)).orderedIds shouldBe
            listOf(1L, 3L)
    }

    @Test
    fun `the chapter being read stays when it is hidden`() {
        order(listOf(chapter(1), chapter(2)), current = 2L, hidden = setOf(2L)).orderedIds shouldBe listOf(1L, 2L)
    }

    @Test
    fun `with Skip read on a forward step passes a read chapter`() {
        order(listOf(chapter(1), chapter(2, read = true), chapter(3)), current = 1L, skipRead = true)
            .chapterAfter(1L) shouldBe 3L
    }

    @Test
    fun `with Skip read off a forward step lands on a read chapter`() {
        order(listOf(chapter(1), chapter(2, read = true), chapter(3)), current = 1L).chapterAfter(1L) shouldBe 2L
    }

    @Test
    fun `a novel with no stored settings skips nothing`() {
        order(listOf(chapter(1), chapter(2, read = true)), current = 1L, skipRead = true, filters = null)
            .chapterAfter(1L) shouldBe 2L
    }

    @Test
    fun `the last chapter has no chapter after it`() {
        order(listOf(chapter(1), chapter(2)), current = 1L).chapterAfter(2L) shouldBe null
    }

    @Test
    fun `with Skip duplicates on one copy of a number is kept`() {
        order(listOf(chapter(1), chapter(2, number = 1.0)), current = 1L, skipDuplicates = true).orderedIds shouldBe
            listOf(1L)
    }

    @Test
    fun `with Downloaded only on a chapter not on disk is stepped over`() {
        order(
            listOf(chapter(1), chapter(2), chapter(3)),
            current = 1L,
            onDisk = setOf(1L, 3L),
            downloadedOnly = true,
        ).orderedIds shouldBe listOf(1L, 3L)
    }

    @Test
    fun `download-ahead walks past what Downloaded only hides`() {
        order(
            listOf(chapter(1), chapter(2), chapter(3)),
            current = 1L,
            onDisk = setOf(1L, 3L),
            downloadedOnly = true,
        ).aheadIds shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `a group-scoped chapter the unified list leaves out is put back`() {
        val other = chapter(9, number = 2.0, novelId = 2L)
        order(listOf(chapter(1), chapter(3)), current = 9L, scope = MergeScope.Group, opened = other)
            .orderedIds shouldBe listOf(1L, 9L, 3L)
    }

    private val online = chapter(1, novelId = 1L)
    private val downloaded = chapter(2, number = 1.0, novelId = 2L)
    private val stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1))
    private val copies = CopyToOpen(MergeScope.Group, listOf(online, downloaded), stitch, { it.id }, setOf(2L))

    @Test
    fun `a merged open reads the copy on disk`() {
        order(
            listOf(online),
            current = 1L,
            scope = MergeScope.Group,
            pooled = listOf(online, downloaded),
            stitch = stitch,
            copies = copies,
            opened = downloaded,
        ).currentId shouldBe 2L
    }

    @Test
    fun `a source-scoped open reads the copy it opened`() {
        order(listOf(online), current = 1L, pooled = listOf(online, downloaded), stitch = stitch, copies = copies)
            .currentId shouldBe 1L
    }
}
