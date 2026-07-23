package reikai.domain.novel.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.librarySortComparator
import reikai.presentation.library.libraryItemSortFields
import reikai.presentation.library.novels.toLibraryItem

/**
 * Characterisation tests for the novel library sort comparator, now a thin adapter over the shared
 * [reikai.domain.library.librarySortComparator]. What were three known divergences from the manga library
 * (the reversed tiebreak, zero-unread ordering, and the Random form) are reconciled: the tiebreak stays
 * A to Z under a descending sort and zero-unread entries sort last regardless of direction, both matching
 * the manga form. The shared comparator itself is pinned in `LibrarySortComparatorTest`; these cases keep
 * the novel adapter honest.
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

    // Drives the real production path: shape each novel into the shared library row, then sort it with
    // the one binding both libraries use, resolving this sort's persisted key to the neutral mode.
    private fun sortedIds(
        novels: List<LibraryNovel>,
        type: NovelLibrarySort.Type,
        ascending: Boolean = true,
        randomSeed: Long = 0L,
        trackerMeanScores: Map<Long, Double> = emptyMap(),
    ): List<Long> {
        val sort = NovelLibrarySort(type, ascending)
        val comparator = librarySortComparator(
            mode = sort.type.toSortMode(),
            isAscending = sort.isAscending,
            randomSeed = randomSeed,
            fields = libraryItemSortFields { trackerMeanScores[it.id] ?: -1.0 },
        )
        return novels
            .map {
                it.toLibraryItem(
                    downloadBadge = false,
                    unreadBadge = false,
                    languageBadge = false,
                    sourceLanguage = "",
                    sourceBadge = false,
                    sourceSite = null,
                    sourceIconUrl = null,
                )
            }
            .sortedWith(comparator)
            .map { it.id }
    }

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

    @Test
    fun `descending keeps the title tiebreak A to Z`() {
        // Reconciled to the manga form: the tiebreak is appended after the reversal, so equal-chapter
        // entries still run A to Z even under a descending sort.
        val novels = listOf(
            libNovel(1, title = "alpha", totalChapters = 5),
            libNovel(2, title = "zeta", totalChapters = 5),
        )

        sortedIds(novels, NovelLibrarySort.Type.TotalChapters, ascending = false) shouldBe listOf(1L, 2L)
    }

    @Test
    fun `unread count sinks a fully-read novel last when ascending`() {
        // Reconciled to the manga form: a zero-unread (fully-read) novel sorts last regardless of
        // direction, so unread content always leads.
        val novels = listOf(
            libNovel(1, title = "a", totalChapters = 10, readCount = 4),
            libNovel(2, title = "b", totalChapters = 10, readCount = 10),
        )

        sortedIds(novels, NovelLibrarySort.Type.UnreadCount) shouldBe listOf(1L, 2L)
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
