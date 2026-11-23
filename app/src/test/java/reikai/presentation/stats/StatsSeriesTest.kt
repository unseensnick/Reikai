package reikai.presentation.stats

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.smartUpdateFacts
import reikai.domain.merge.MergedGroupCounts
import reikai.domain.merge.TestMergeManagers
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import kotlin.time.Instant

/**
 * Library members 1 and 2 are one merged series in every case; the ids are each type's own. Both merge
 * managers are the shared EntryMergeManager and statsSeries is generic over the row, so a case runs once,
 * on manga. Only the started and completed counts read each type's own smartUpdateFacts adapter, and
 * those run over both types.
 */
class StatsSeriesTest {

    @Test
    fun `a merged series is one title`() = runTest {
        series(mergingOn = true).titles.size shouldBe 1
    }

    // The library row ranks its members to pick a lead, and the series' status and categories are the lead's.
    @Test
    fun `a merged series stands as the member its library ranking leads with`() = runTest {
        val managers = TestMergeManagers(mapOf(ContentType.MANGA to mapOf(1L to 9L, 2L to 9L)), mergingOn = true)
        val leadId = managers.manga.statsSeries(listOf(manga(1L), manga(2L)), emptyMap(), rank = { m, _ ->
            m.reversed()
        }) { it.id }.titles.single().id

        leadId shouldBe 2L
    }

    @Test
    fun `with series merging off each source is its own title`() = runTest {
        series(mergingOn = false).titles.size shouldBe 2
    }

    /** A member's sessions and its library row reach Statistics' series through this map. */
    @Test
    fun `every member of a merged series maps to the series' title`() = runTest {
        series(mergingOn = true).titleIdByMember shouldBe mapOf(1L to 1L, 2L to 1L)
    }

    @Test
    fun `with series merging off each member maps to itself`() = runTest {
        series(mergingOn = false).titleIdByMember shouldBe mapOf(1L to 1L, 2L to 2L)
    }

    /** Chapter totals and downloads are per-source rows and files, so they sum every member. */
    @Test
    fun `a merged series' per-source sums reach every member`() = runTest {
        series(mergingOn = true).members.size shouldBe 2
    }

    @Test
    fun `a tracker bound only on a later member tracks the series`() = runTest {
        series(mergingOn = true).trackedCount(tracksOnSecondMember) shouldBe 1
    }

    @Test
    fun `a score bound only on a later member is the series' mean`() = runTest {
        series(mergingOn = true).meanScores(tracksOnSecondMember, trackers).toList() shouldBe listOf(7.0)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a completed series read to the end counts as completed`(type: ContentType) = runTest {
        completedCount(type, status = COMPLETED, readCount = 3) shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a completed series with unread chapters is not completed`(type: ContentType) = runTest {
        completedCount(type, status = COMPLETED, readCount = 2) shouldBe 0
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an ongoing series read to the end is not completed`(type: ContentType) = runTest {
        completedCount(type, status = ONGOING, readCount = 3) shouldBe 0
    }

    /** In global update beside In library: each source is updated, but the series is one title. */
    @Test
    fun `a merged series the global update reaches counts as one title in it`() = runTest {
        globalUpdateCount(updatedIds = setOf(1L, 2L)) shouldBe 1
    }

    @Test
    fun `a merged series counts in the global update when only a later member is updated`() = runTest {
        globalUpdateCount(updatedIds = setOf(2L)) shouldBe 1
    }

    /** The lead source has read nothing; the stitch reads the series from the other source's copies. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series read only on a later member counts as started`(type: ContentType) = runTest {
        mergedSeries(type, leadRead = 0, stitch = MergedGroupCounts(total = 3, read = 2, bookmarked = 0))
            .started shouldBe 1
    }

    /** The lead has read all of its own chapters, but the group covers one more. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series with a chapter unread on another member is not completed`(type: ContentType) = runTest {
        mergedSeries(type, leadRead = 3, stitch = MergedGroupCounts(total = 4, read = 3, bookmarked = 0))
            .completed shouldBe 0
    }

    @Test
    fun `a merged series' title lists every member's source`() = runTest {
        mergedTitle().sources.map { it.key } shouldBe listOf("1", "2")
    }

    @Test
    fun `a merged series' title takes its chapters from the stitch`() = runTest {
        mergedTitle(stitch = MergedGroupCounts(total = 5, read = 4, bookmarked = 0))
            .let { it.totalChapters to it.readChapters } shouldBe (5L to 4L)
    }

    @Test
    fun `a merged series' reading time sums every member`() = runTest {
        mergedTitle(readDuration = mapOf(1L to 10L, 2L to 5L)).readDuration shouldBe 15L
    }

    @Test
    fun `a tracker start date on a later member starts the series`() = runTest {
        val started = Instant.parse("2024-03-01T00:00:00Z")
        val tracks = mapOf(2L to listOf(track(entryId = 2L, score = 0.0).copy(startDate = started)))

        mergedTitle(tracks = tracks, trackers = relaxedTrackers).trackerStartedAt shouldBe started
    }

    private fun mergedManagers() =
        TestMergeManagers(mapOf(ContentType.MANGA to mapOf(1L to 9L, 2L to 9L)), mergingOn = true)

    /** Members 1 and 2 merged in group 9, as one [StatsTitle]. */
    private suspend fun mergedTitle(
        stitch: MergedGroupCounts? = null,
        readDuration: Map<Long, Long> = emptyMap(),
        tracks: Map<Long, List<Track>> = emptyMap(),
        trackers: Map<Long, Tracker> = this.trackers,
    ): StatsTitle {
        val counts = stitch?.let { mapOf(9L to it) }.orEmpty()
        return mergedManagers().manga.statsSeries(listOf(manga(1L, totalChapters = 3), manga(2L)), counts) {
            it.id
        }.statsTitles(
            titleFields({ it.id }, { it.totalChapters }, { it.readCount }),
            tracks,
            trackers,
            readDuration,
            emptyMap(),
        ).single()
    }

    private fun <T> titleFields(id: (T) -> Long, total: (T) -> Long, read: (T) -> Long) = StatsTitleFields<T>(
        entryId = { EntryId.Manga(id(it)) },
        title = { "" },
        status = { 0L },
        source = { StatsSource(id(it).toString(), "source", "en") },
        genres = { emptyList() },
        customGenres = { null },
        categoryIds = { emptyList() },
        totalChapters = total,
        readCount = read,
    )

    private val relaxedTrackers = mapOf(1L to mockk<Tracker>(relaxed = true))

    /** Members 1 (the lead, three chapters, [leadRead] read, completed) and 2, merged in group 9. */
    private suspend fun mergedSeries(type: ContentType, leadRead: Long, stitch: MergedGroupCounts): StatusCounts {
        val managers = TestMergeManagers(mapOf(type to mapOf(1L to 9L, 2L to 9L)), mergingOn = true)
        val counts = mapOf(9L to stitch)
        return when (type) {
            ContentType.MANGA -> managers.manga.statsSeries(
                listOf(manga(1L, COMPLETED, 3, leadRead), manga(2L, COMPLETED, 4, 3)),
                counts,
            ) {
                it.id
            }.let {
                StatusCounts(
                    it.startedCount { m ->
                        m.smartUpdateFacts()
                    },
                    it.completedCount { m -> m.smartUpdateFacts() },
                )
            }
            else -> managers.novel.statsSeries(
                listOf(novel(1L, COMPLETED, 3, leadRead), novel(2L, COMPLETED, 4, 3)),
                counts,
            ) {
                it.id
            }.let {
                StatusCounts(
                    it.startedCount { n ->
                        n.smartUpdateFacts()
                    },
                    it.completedCount { n -> n.smartUpdateFacts() },
                )
            }
        }
    }

    private data class StatusCounts(val started: Int, val completed: Int)

    private suspend fun globalUpdateCount(updatedIds: Set<Long>): Int =
        mergedManagers().manga.statsSeries(listOf(manga(2L), manga(1L)), emptyMap()) { it.id }
            .globalUpdateCount { it.id in updatedIds }

    private val tracksOnSecondMember = mapOf(2L to listOf(track(entryId = 2L, score = 7.0)))

    private val trackers = mapOf(
        1L to mockk<Tracker> { every { get10PointScore(any()) } answers { firstArg<Track>().score } },
    )

    private suspend fun series(mergingOn: Boolean): StatsSeries<*> {
        val managers = TestMergeManagers(mapOf(ContentType.MANGA to mapOf(1L to 9L, 2L to 9L)), mergingOn)
        return managers.manga.statsSeries(listOf(manga(2L), manga(1L)), emptyMap()) { it.id }
    }

    /** One three-chapter title of [type], merging off. */
    private suspend fun completedCount(type: ContentType, status: Long, readCount: Long): Int {
        val managers = TestMergeManagers(emptyMap(), mergingOn = false)
        return when (type) {
            ContentType.MANGA -> managers.manga.statsSeries(listOf(manga(1L, status, 3, readCount)), emptyMap()) {
                it.id
            }
                .completedCount { it.smartUpdateFacts() }
            else -> managers.novel.statsSeries(listOf(novel(1L, status, 3, readCount)), emptyMap()) { it.id }
                .completedCount { it.smartUpdateFacts() }
        }
    }

    private fun manga(id: Long, status: Long = 0, totalChapters: Long = 0, readCount: Long = 0) = LibraryManga(
        manga = Manga.create().copy(id = id, status = status),
        categories = emptyList(),
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )

    private fun novel(id: Long, status: Long = 0, totalChapters: Long = 0, readCount: Long = 0) = LibraryNovel(
        novel = Novel.create().copy(id = id, status = status),
        categories = emptyList(),
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )

    private fun track(entryId: Long, score: Double) = Track(
        id = entryId,
        mangaId = entryId,
        trackerId = 1L,
        remoteId = 0L,
        libraryId = null,
        title = "",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = score,
        remoteUrl = "",
        startDate = null,
        finishDate = null,
        private = false,
    )

    private companion object {
        // SManga and NovelStatusCode share these values.
        const val ONGOING = 1L
        const val COMPLETED = 2L
    }
}
