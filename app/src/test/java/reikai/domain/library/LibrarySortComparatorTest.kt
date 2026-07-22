package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibrarySortComparatorTest {

    private data class Row(
        val id: Long,
        val title: String = "",
        val lastRead: Long = 0,
        val lastUpdate: Long = 0,
        val unreadCount: Long = 0,
        val totalChapters: Long = 0,
        val latestUpload: Long = 0,
        val chapterFetchedAt: Long = 0,
        val dateAdded: Long = 0,
        val downloadCount: Long = 0,
        val trackerMean: Double = -1.0,
    )

    private val fields = LibrarySortFields<Row>(
        id = { it.id },
        title = { it.title },
        lastRead = { it.lastRead },
        lastUpdate = { it.lastUpdate },
        unreadCount = { it.unreadCount },
        totalChapters = { it.totalChapters },
        latestUpload = { it.latestUpload },
        chapterFetchedAt = { it.chapterFetchedAt },
        dateAdded = { it.dateAdded },
        downloadCount = { it.downloadCount },
        trackerMean = { it.trackerMean },
    )

    private fun sortedIds(
        rows: List<Row>,
        mode: LibrarySortMode,
        isAscending: Boolean = true,
        seed: Long = 0L,
    ): List<Long> = rows.sortedWith(librarySortComparator(mode, isAscending, seed, fields)).map { it.id }

    @Test
    fun `alphabetical is case-insensitive ascending`() {
        val rows = listOf(Row(1, "beta"), Row(2, "Alpha"), Row(3, "gamma"))
        sortedIds(rows, LibrarySortMode.Alphabetical) shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `alphabetical descending reverses`() {
        val rows = listOf(Row(1, "Alpha"), Row(2, "beta"), Row(3, "gamma"))
        sortedIds(rows, LibrarySortMode.Alphabetical, isAscending = false) shouldBe listOf(3L, 2L, 1L)
    }

    @Test
    fun `alphabetical uses the locale collator, not code-point order`() {
        // With the collator an accented letter sorts near its base letter (before "z"); plain code-point
        // order would push it after "z" (U+00E4 > 'z'). Proves the collator form, leveling novels up.
        val rows = listOf(Row(1, "Zebra"), Row(2, "ähnlich"))
        val order = sortedIds(rows, LibrarySortMode.Alphabetical)
        order.indexOf(2L) shouldBe 0
    }

    @Test
    fun `numeric modes compare their field ascending`() {
        val rows = listOf(Row(1, totalChapters = 10), Row(2, totalChapters = 3), Row(3, totalChapters = 7))
        sortedIds(rows, LibrarySortMode.TotalChapters) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `last read sorts by its field`() {
        val rows = listOf(Row(1, lastRead = 500), Row(2, lastRead = 100), Row(3, lastRead = 300))
        sortedIds(rows, LibrarySortMode.LastRead) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `downloaded sorts by download count`() {
        val rows = listOf(Row(1, downloadCount = 2), Row(2, downloadCount = 9), Row(3, downloadCount = 0))
        sortedIds(rows, LibrarySortMode.Downloaded) shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `unread count puts non-zero entries in order, ascending`() {
        val rows = listOf(Row(1, unreadCount = 5), Row(2, unreadCount = 1), Row(3, unreadCount = 3))
        sortedIds(rows, LibrarySortMode.UnreadCount) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `unread count sinks zero-unread entries last when ascending`() {
        val rows = listOf(Row(1, unreadCount = 0), Row(2, unreadCount = 3), Row(3, unreadCount = 1))
        sortedIds(rows, LibrarySortMode.UnreadCount) shouldBe listOf(3L, 2L, 1L)
    }

    @Test
    fun `unread count keeps zero-unread entries last even when descending`() {
        // The reconciled manga form: a fully-read entry never leads the Unread sort, either direction.
        val rows = listOf(Row(1, unreadCount = 0), Row(2, unreadCount = 3), Row(3, unreadCount = 1))
        sortedIds(rows, LibrarySortMode.UnreadCount, isAscending = false) shouldBe listOf(2L, 3L, 1L)
    }

    @Test
    fun `the title tiebreak stays A to Z under a descending sort`() {
        // The reconciled manga form: the tiebreak is appended after the reversal, so equal-key entries
        // still run A to Z even when the primary sort is descending.
        val rows = listOf(Row(1, "Zeta", totalChapters = 5), Row(2, "Alpha", totalChapters = 5))
        sortedIds(rows, LibrarySortMode.TotalChapters, isAscending = false) shouldBe listOf(2L, 1L)
    }

    @Test
    fun `tracker mean sinks the unscored default below any real score`() {
        val rows = listOf(Row(1, trackerMean = -1.0), Row(2, trackerMean = 8.0), Row(3, trackerMean = 4.0))
        sortedIds(rows, LibrarySortMode.TrackerMean) shouldBe listOf(1L, 3L, 2L)
    }

    @Test
    fun `random is stable for a given seed`() {
        val rows = listOf(Row(1), Row(2), Row(3), Row(4), Row(5))
        val first = sortedIds(rows, LibrarySortMode.Random, seed = 42L)
        val second = sortedIds(rows.shuffled(), LibrarySortMode.Random, seed = 42L)
        first shouldBe second
    }

    @Test
    fun `random reorders when the seed changes`() {
        val rows = (1L..8L).map { Row(it) }
        val a = sortedIds(rows, LibrarySortMode.Random, seed = 1L)
        val b = sortedIds(rows, LibrarySortMode.Random, seed = 2L)
        (a != b) shouldBe true
    }
}
