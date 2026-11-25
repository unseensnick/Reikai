package reikai.domain.novel.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.librarySortComparator
import reikai.domain.library.toSortMode
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.SourceBadge
import reikai.presentation.library.libraryItemSortFields
import reikai.presentation.library.novels.toLibraryItem
import tachiyomi.domain.library.model.LibrarySort
import kotlin.time.Instant

/**
 * The novel row's binding onto the shared sort kernel: each mode reads the novel field it names. The
 * kernel's own rules (direction, tiebreak, zero-unread, Random) are pinned in `LibrarySortComparatorTest`.
 */
class NovelLibrarySortComparatorTest {

    private fun libNovel(
        id: Long,
        title: String = "title$id",
        lastRead: Long = 0,
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
            lastUpdate = Instant.fromEpochMilliseconds(lastUpdate),
            favoriteAt = Instant.fromEpochMilliseconds(dateAdded),
        ),
        categories = emptyList(),
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0,
        downloadCount = downloadCount,
        latestUpload = Instant.fromEpochMilliseconds(latestUpload),
        chapterFetchedAt = Instant.fromEpochMilliseconds(chapterFetchedAt),
        lastRead = Instant.fromEpochMilliseconds(lastRead),
    )

    // Drives the real production path: shape each novel into the shared library row, then sort it with
    // the one binding both libraries use, resolving this sort's persisted key to the neutral mode.
    private fun sortedIds(
        novels: List<LibraryNovel>,
        type: LibrarySort.Type,
        trackerMeanScores: Map<Long, Double> = emptyMap(),
    ): List<Long> {
        val sort = LibrarySort(type, LibrarySort.Direction.Ascending)
        val comparator = librarySortComparator(
            mode = sort.type.toSortMode(),
            isAscending = sort.isAscending,
            randomSeed = 0L,
            fields = libraryItemSortFields { trackerMeanScores[it.id] },
        )
        return novels
            .map {
                it.toLibraryItem(
                    badgePrefs = LibraryBadgePrefs(false, false, false, false, false),
                    sourceLanguage = "",
                    sourceIcon = SourceBadge.Generic,
                    sourceName = "",
                )
            }
            .sortedWith(comparator)
            .map { it.id }
    }

    @Test
    fun `alphabetical sorts by title ignoring case`() {
        val novels = listOf(libNovel(1, "beta"), libNovel(2, "Alpha"), libNovel(3, "gamma"))

        sortedIds(novels, LibrarySort.Type.Alphabetical) shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `last read sorts by the latest read in history`() {
        val novels =
            listOf(libNovel(1, lastRead = 300), libNovel(2, lastRead = 0), libNovel(3, lastRead = 100))

        sortedIds(novels, LibrarySort.Type.LastRead) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `last update sorts by the novel's update timestamp`() {
        val novels = listOf(libNovel(1, lastUpdate = 30), libNovel(2, lastUpdate = 10), libNovel(3, lastUpdate = 20))

        sortedIds(novels, LibrarySort.Type.LastUpdate) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `unread count sorts by total minus read`() {
        val novels = listOf(
            libNovel(1, totalChapters = 10, readCount = 2),
            libNovel(2, totalChapters = 10, readCount = 9),
            libNovel(3, totalChapters = 10, readCount = 5),
        )

        sortedIds(novels, LibrarySort.Type.UnreadCount) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `total chapters sorts by chapter count`() {
        val novels = listOf(libNovel(1, totalChapters = 30), libNovel(2, totalChapters = 10))

        sortedIds(novels, LibrarySort.Type.TotalChapters) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `latest chapter sorts by latest upload`() {
        val novels = listOf(libNovel(1, latestUpload = 30), libNovel(2, latestUpload = 10))

        sortedIds(novels, LibrarySort.Type.LatestChapter) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `chapter fetch date sorts by fetch timestamp`() {
        val novels = listOf(libNovel(1, chapterFetchedAt = 30), libNovel(2, chapterFetchedAt = 10))

        sortedIds(novels, LibrarySort.Type.ChapterFetchDate) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `date added sorts by when the novel was favorited`() {
        val novels = listOf(libNovel(1, dateAdded = 30), libNovel(2, dateAdded = 10))

        sortedIds(novels, LibrarySort.Type.DateAdded) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `downloaded sorts by download count`() {
        val novels =
            listOf(libNovel(1, downloadCount = 5), libNovel(2, downloadCount = 0), libNovel(3, downloadCount = 2))

        sortedIds(novels, LibrarySort.Type.Downloaded) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `tracker mean sorts by the supplied score`() {
        val novels = listOf(libNovel(1), libNovel(2), libNovel(3))
        val scores = mapOf(1L to 8.0, 2L to 3.0, 3L to 6.0)

        sortedIds(novels, LibrarySort.Type.TrackerMean, trackerMeanScores = scores) shouldBe listOf(2L, 3L, 1L)
    }
}
