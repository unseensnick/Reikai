package reikai.domain.novel.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Characterisation tests for the novel library sort comparator: they pin what it does TODAY so the
 * content-layer pipeline share can tell an intended behaviour change from an accidental one. Three
 * cases below (the reversed tiebreak, zero-unread ordering, and the Random form) are known divergences
 * from the manga library that the shared comparator will reconcile; each is marked, and the test is
 * expected to be updated, not deleted, when that lands.
 */
class NovelLibrarySortComparatorTest {

    private fun libNovel(
        id: Long,
        title: String = "title$id",
        lastReadAt: Long? = null,
        lastUpdate: Long = 0,
        totalChapters: Long = 0,
        readCount: Long = 0,
        downloadCount: Long = 0,
        latestUpload: Long = 0,
        chapterFetchedAt: Long = 0,
        dateAdded: Long = 0,
    ) = LibraryNovel(
        novel = Novel.create().copy(
            id = id,
            title = title,
            favorite = true,
            lastUpdate = lastUpdate,
            dateAdded = dateAdded,
            lastReadAt = lastReadAt,
        ),
        categories = emptyList(),
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0,
        downloadCount = downloadCount,
        latestUpload = latestUpload,
        chapterFetchedAt = chapterFetchedAt,
    )

    private fun sortedIds(
        novels: List<LibraryNovel>,
        type: NovelLibrarySort.Type,
        ascending: Boolean = true,
        randomSeed: Long = 0L,
        trackerMeanScores: Map<Long, Double> = emptyMap(),
    ): List<Long> = novels
        .sortedWith(NovelLibrarySort(type, ascending).comparator(randomSeed, trackerMeanScores))
        .map { it.id }

    @Test
    fun `alphabetical sorts by title ignoring case`() {
        val novels = listOf(libNovel(1, "beta"), libNovel(2, "Alpha"), libNovel(3, "gamma"))

        sortedIds(novels, NovelLibrarySort.Type.Alphabetical) shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `descending reverses the sort`() {
        val novels = listOf(libNovel(1, "beta"), libNovel(2, "Alpha"), libNovel(3, "gamma"))

        sortedIds(novels, NovelLibrarySort.Type.Alphabetical, ascending = false) shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `last read sorts by the denormalised last-read timestamp`() {
        val novels =
            listOf(libNovel(1, lastReadAt = 300), libNovel(2, lastReadAt = null), libNovel(3, lastReadAt = 100))

        sortedIds(novels, NovelLibrarySort.Type.LastRead) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `last update sorts by the novel's update timestamp`() {
        val novels = listOf(libNovel(1, lastUpdate = 30), libNovel(2, lastUpdate = 10), libNovel(3, lastUpdate = 20))

        sortedIds(novels, NovelLibrarySort.Type.LastUpdate) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `unread count sorts by total minus read`() {
        val novels = listOf(
            libNovel(1, totalChapters = 10, readCount = 2),
            libNovel(2, totalChapters = 10, readCount = 9),
            libNovel(3, totalChapters = 10, readCount = 5),
        )

        sortedIds(novels, NovelLibrarySort.Type.UnreadCount) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `total chapters sorts by chapter count`() {
        val novels = listOf(libNovel(1, totalChapters = 30), libNovel(2, totalChapters = 10))

        sortedIds(novels, NovelLibrarySort.Type.TotalChapters) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `latest chapter sorts by latest upload`() {
        val novels = listOf(libNovel(1, latestUpload = 30), libNovel(2, latestUpload = 10))

        sortedIds(novels, NovelLibrarySort.Type.LatestChapter) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `chapter fetch date sorts by fetch timestamp`() {
        val novels = listOf(libNovel(1, chapterFetchedAt = 30), libNovel(2, chapterFetchedAt = 10))

        sortedIds(novels, NovelLibrarySort.Type.ChapterFetchDate) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `date added sorts by when the novel was favorited`() {
        val novels = listOf(libNovel(1, dateAdded = 30), libNovel(2, dateAdded = 10))

        sortedIds(novels, NovelLibrarySort.Type.DateAdded) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `downloaded sorts by download count`() {
        val novels =
            listOf(libNovel(1, downloadCount = 5), libNovel(2, downloadCount = 0), libNovel(3, downloadCount = 2))

        sortedIds(novels, NovelLibrarySort.Type.Downloaded) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `tracker mean sorts by the supplied score`() {
        val novels = listOf(libNovel(1), libNovel(2), libNovel(3))
        val scores = mapOf(1L to 8.0, 2L to 3.0, 3L to 6.0)

        sortedIds(novels, NovelLibrarySort.Type.TrackerMean, trackerMeanScores = scores) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `tracker mean puts unscored novels below every scored one when ascending`() {
        val novels = listOf(libNovel(1), libNovel(2))
        val scores = mapOf(1L to 0.5)

        sortedIds(novels, NovelLibrarySort.Type.TrackerMean, trackerMeanScores = scores) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `title breaks ties for every sort`() {
        val novels = listOf(
            libNovel(1, title = "zeta", totalChapters = 5),
            libNovel(2, title = "alpha", totalChapters = 5),
        )

        sortedIds(novels, NovelLibrarySort.Type.TotalChapters) shouldBe listOf(2L, 1L)
    }

    /**
     * Known divergence from the manga library, pinned deliberately. Novels reverse the whole
     * comparator including the title tiebreak, so ties run Z to A under a descending sort; manga
     * appends the tiebreak after reversing, keeping ties A to Z. The shared comparator adopts the
     * manga form, at which point this expectation flips.
     */
    @Test
    fun `descending currently reverses the title tiebreak too`() {
        val novels = listOf(
            libNovel(1, title = "alpha", totalChapters = 5),
            libNovel(2, title = "zeta", totalChapters = 5),
        )

        sortedIds(novels, NovelLibrarySort.Type.TotalChapters, ascending = false) shouldBe listOf(2L, 1L)
    }

    /**
     * Known divergence from the manga library, pinned deliberately. Manga forces zero-unread entries
     * last regardless of direction so unread content always leads, which only shows under an ascending
     * sort (descending sinks them anyway). Novels compare the raw number, so a fully-read novel leads.
     * The shared comparator adopts the manga form, at which point this expectation flips to 1 then 2.
     */
    @Test
    fun `unread count currently lets a fully-read novel lead when ascending`() {
        val novels = listOf(
            libNovel(1, title = "a", totalChapters = 10, readCount = 4),
            libNovel(2, title = "b", totalChapters = 10, readCount = 10),
        )

        sortedIds(novels, NovelLibrarySort.Type.UnreadCount) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `random is stable for a given seed`() {
        val novels = (1L..8L).map { libNovel(it) }

        val first = sortedIds(novels, NovelLibrarySort.Type.Random, randomSeed = 42L)
        val second = sortedIds(novels.shuffled(), NovelLibrarySort.Type.Random, randomSeed = 42L)

        first shouldBe second
    }

    @Test
    fun `random reorders when the seed changes`() {
        val novels = (1L..20L).map { libNovel(it) }

        val seedA = sortedIds(novels, NovelLibrarySort.Type.Random, randomSeed = 1L)
        val seedB = sortedIds(novels, NovelLibrarySort.Type.Random, randomSeed = 2L)

        (seedA == seedB) shouldBe false
    }
}
