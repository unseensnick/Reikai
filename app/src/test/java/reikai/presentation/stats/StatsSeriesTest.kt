package reikai.presentation.stats

import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.library.smartUpdateFacts
import reikai.domain.merge.TestMergeManagers
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

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

    private suspend fun globalUpdateCount(type: ContentType, updatedIds: Set<Long>): Int {
        val managers = TestMergeManagers(mapOf(type to mapOf(1L to 9L, 2L to 9L)), mergingOn = true)
        return when (type) {
            ContentType.MANGA -> managers.manga.statsSeries(listOf(manga(2L), manga(1L))) { it.id }
                .globalUpdateCount { it.id in updatedIds }
            else -> managers.novel.statsSeries(listOf(novel(2L), novel(1L))) { it.id }
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
            ContentType.MANGA -> managers.manga.statsSeries(listOf(manga(2L), manga(1L))) { it.id }
            else -> managers.novel.statsSeries(listOf(novel(2L), novel(1L))) { it.id }
        }
    }

    /** One three-chapter title of [type], merging off. */
    private suspend fun completedCount(type: ContentType, status: Long, readCount: Long): Int {
        val managers = TestMergeManagers(emptyMap(), mergingOn = false)
        return when (type) {
            ContentType.MANGA -> managers.manga.statsSeries(listOf(manga(1L, status, 3, readCount))) { it.id }
                .completedCount { it.smartUpdateFacts() }
            else -> managers.novel.statsSeries(listOf(novel(1L, status, 3, readCount))) { it.id }
                .completedCount { it.smartUpdateFacts() }
        }
    }

    private fun manga(id: Long, status: Long = 0, totalChapters: Long = 0, readCount: Long = 0) = LibraryManga(
        manga = Manga.create().copy(id = id, status = status),
        categories = emptyList(),
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private fun novel(id: Long, status: Long = 0, totalChapters: Long = 0, readCount: Long = 0) = LibraryNovel(
        novel = Novel.create().copy(id = id, status = status),
        categories = emptyList(),
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
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
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private companion object {
        // SManga and NovelStatusCode share these values.
        const val ONGOING = 1L
        const val COMPLETED = 2L
    }
}
