package reikai.presentation.stats

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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

/** Library members 1 and 2 are one merged series in every case; the ids are each type's own. */
class StatsSeriesTest {

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series is one title`(type: ContentType) = runTest {
        series(type, mergingOn = true).titles.size shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `with series merging off each source is its own title`(type: ContentType) = runTest {
        series(type, mergingOn = false).titles.size shouldBe 2
    }

    /** Chapter totals and downloads are per-source rows and files, so they sum every member. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series' per-source sums reach every member`(type: ContentType) = runTest {
        series(type, mergingOn = true).members.size shouldBe 2
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a tracker bound only on a later member tracks the series`(type: ContentType) = runTest {
        series(type, mergingOn = true).trackedCount(tracksOnSecondMember) shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a score bound only on a later member is the series' mean`(type: ContentType) = runTest {
        series(type, mergingOn = true).meanScores(tracksOnSecondMember, trackers).toList() shouldBe listOf(7.0)
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
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series the global update reaches counts as one title in it`(type: ContentType) = runTest {
        globalUpdateCount(type, updatedIds = setOf(1L, 2L)) shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series counts in the global update when only a later member is updated`(type: ContentType) =
        runTest {
            globalUpdateCount(type, updatedIds = setOf(2L)) shouldBe 1
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

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series' title lists every member's source`(type: ContentType) = runTest {
        mergedTitle(type).sources.map { it.key } shouldBe listOf("1", "2")
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series' title takes its chapters from the stitch`(type: ContentType) = runTest {
        mergedTitle(type, stitch = MergedGroupCounts(total = 5, read = 4, bookmarked = 0))
            .let { it.totalChapters to it.readChapters } shouldBe (5L to 4L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merged series' reading time sums every member`(type: ContentType) = runTest {
        mergedTitle(type, readDuration = mapOf(1L to 10L, 2L to 5L)).readDuration shouldBe 15L
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a tracker start date on a later member starts the series`(type: ContentType) = runTest {
        val started = Instant.parse("2024-03-01T00:00:00Z")
        val tracks = mapOf(2L to listOf(track(entryId = 2L, score = 0.0).copy(startDate = started)))

        mergedTitle(type, tracks = tracks, trackers = relaxedTrackers).trackerStartedAt shouldBe started
    }

    /** Members 1 and 2 merged in group 9, as one [StatsTitle]. */
    private suspend fun mergedTitle(
        type: ContentType,
        stitch: MergedGroupCounts? = null,
        readDuration: Map<Long, Long> = emptyMap(),
        tracks: Map<Long, List<Track>> = emptyMap(),
        trackers: Map<Long, Tracker> = this.trackers,
    ): StatsTitle {
        val managers = TestMergeManagers(mapOf(type to mapOf(1L to 9L, 2L to 9L)), mergingOn = true)
        val counts = stitch?.let { mapOf(9L to it) }.orEmpty()
        return when (type) {
            ContentType.MANGA -> managers.manga.statsSeries(listOf(manga(1L, totalChapters = 3), manga(2L)), counts) {
                it.id
            }.statsTitles(
                titleFields({ it.id }, { it.totalChapters }, { it.readCount }),
                tracks,
                trackers,
                readDuration,
                emptyMap(),
            )
            else -> managers.novel.statsSeries(listOf(novel(1L, totalChapters = 3), novel(2L)), counts) {
                it.id
            }.statsTitles(
                titleFields({ it.id }, { it.totalChapters }, { it.readCount }),
                tracks,
                trackers,
                readDuration,
                emptyMap(),
            )
        }.single()
    }

    private fun <T> titleFields(id: (T) -> Long, total: (T) -> Long, read: (T) -> Long) = StatsTitleFields<T>(
        entryId = { EntryId.Manga(id(it)) },
        title = { "" },
        status = { 0L },
        source = { StatsSource(id(it).toString(), "source", "en") },
        genres = { emptyList() },
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

    private suspend fun globalUpdateCount(type: ContentType, updatedIds: Set<Long>): Int {
        val managers = TestMergeManagers(mapOf(type to mapOf(1L to 9L, 2L to 9L)), mergingOn = true)
        return when (type) {
            ContentType.MANGA -> managers.manga.statsSeries(listOf(manga(2L), manga(1L)), emptyMap()) { it.id }
                .globalUpdateCount { it.id in updatedIds }
            else -> managers.novel.statsSeries(listOf(novel(2L), novel(1L)), emptyMap()) { it.id }
                .globalUpdateCount { it.id in updatedIds }
        }
    }

    private val tracksOnSecondMember = mapOf(2L to listOf(track(entryId = 2L, score = 7.0)))

    private val trackers = mapOf(
        1L to mockk<Tracker> { every { get10PointScore(any()) } answers { firstArg<Track>().score } },
    )

    private suspend fun series(type: ContentType, mergingOn: Boolean): StatsSeries<*> {
        val managers = TestMergeManagers(mapOf(type to mapOf(1L to 9L, 2L to 9L)), mergingOn)
        return when (type) {
            ContentType.MANGA -> managers.manga.statsSeries(listOf(manga(2L), manga(1L)), emptyMap()) { it.id }
            else -> managers.novel.statsSeries(listOf(novel(2L), novel(1L)), emptyMap()) { it.id }
        }
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
