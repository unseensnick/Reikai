package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/**
 * Restoring over a series the device already has merges the two copies by the same rules for both
 * content types: the newer version's details win, a chapter keeps read and bookmark from either side and
 * the further progress, and a bound track keeps the device's row with the backup's remote link and the
 * further chapter read. Runs each type's real restorer over one series and one chapter or track.
 */
class RestoreMergeConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `an older backup keeps the device's newer details`(restorer: MergeRestorer) = runTest {
        restorer.description(deviceVersion = 5, backupVersion = 2) shouldBe "device"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `a newer backup replaces the device's older details`(restorer: MergeRestorer) = runTest {
        restorer.description(deviceVersion = 2, backupVersion = 5) shouldBe "backup"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `a backup behind the device does not rewind a chapter's progress`(restorer: MergeRestorer) = runTest {
        restorer.chapter(device = ChapterState(progress = 30), backup = ChapterState(progress = 10)) shouldBe
            ChapterState(progress = 30)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `a backup ahead of the device carries its chapter progress`(restorer: MergeRestorer) = runTest {
        restorer.chapter(device = ChapterState(progress = 10), backup = ChapterState(progress = 30)) shouldBe
            ChapterState(progress = 30)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `read and bookmark on the device survive a backup without them`(restorer: MergeRestorer) = runTest {
        restorer.chapter(
            device = ChapterState(read = true, bookmark = true, progress = 5),
            backup = ChapterState(progress = 1),
        ) shouldBe ChapterState(read = true, bookmark = true, progress = 5)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `read and bookmark in the backup reach the device`(restorer: MergeRestorer) = runTest {
        restorer.chapter(
            device = ChapterState(progress = 5),
            backup = ChapterState(read = true, bookmark = true, progress = 1),
        ) shouldBe ChapterState(read = true, bookmark = true, progress = 5)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restorers")
    fun `a bound track keeps the device's row and takes the backup's link and further chapter`(
        restorer: MergeRestorer,
    ) = runTest {
        restorer.track(
            device = TrackState(remoteId = 1, libraryId = 1, status = COMPLETED, score = 9.0, lastChapterRead = 5.0),
            backup = TrackState(remoteId = 2, libraryId = 2, status = READING, score = 0.0, lastChapterRead = 8.0),
        ) shouldBe TrackState(remoteId = 2, libraryId = 2, status = COMPLETED, score = 9.0, lastChapterRead = 8.0)
    }

    companion object {
        const val DEVICE_ID = 10L
        const val TRACKER_ID = 3L
        const val READING = 1L
        const val COMPLETED = 2L

        @JvmStatic
        fun restorers() = listOf(MangaMergeRestorer(), NovelMergeRestorer())
    }
}

data class ChapterState(val read: Boolean = false, val bookmark: Boolean = false, val progress: Long = 0)

data class TrackState(
    val remoteId: Long,
    val libraryId: Long?,
    val status: Long,
    val score: Double,
    val lastChapterRead: Double,
)

/** One type's restore of a single series already on the device under [RestoreMergeConformanceTest.DEVICE_ID]. */
interface MergeRestorer {

    /** Restores a series described "backup" over one described "device", returning the description kept. */
    suspend fun description(deviceVersion: Long, backupVersion: Long): String?

    /** Restores one chapter over the device's copy, returning the device's state afterwards. */
    suspend fun chapter(device: ChapterState, backup: ChapterState): ChapterState

    /** Restores one track over the device's track on the same tracker, returning the device's row afterwards. */
    suspend fun track(device: TrackState, backup: TrackState): TrackState
}

class MangaMergeRestorer : MergeRestorer {

    override fun toString() = "manga"

    private val deviceManga = Manga.create().copy(id = RestoreMergeConformanceTest.DEVICE_ID, url = "u", source = 1L)

    override suspend fun description(deviceVersion: Long, backupVersion: Long): String? {
        var written: String? = null
        val database = database {
            coEvery {
                mangasQueries.update(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                written = arg(4)
                0L
            }
        }
        restorer(database, deviceManga.copy(description = "device", version = deviceVersion))
            .restore(BackupManga(source = 1L, url = "u", description = "backup", version = backupVersion), emptyList())
        return written
    }

    override suspend fun chapter(device: ChapterState, backup: ChapterState): ChapterState {
        var result = device
        val database = database {
            coEvery {
                chaptersQueries.update(
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                result = ChapterState(arg(4), arg(5), arg(6))
                0L
            }
        }
        val dbChapter = Chapter.create().copy(
            id = 1,
            mangaId = RestoreMergeConformanceTest.DEVICE_ID,
            url = "c",
            name = "C",
            read = device.read,
            bookmark = device.bookmark,
            lastPageRead = device.progress,
        )
        val backupChapter = BackupChapter(
            url = "c",
            name = "C",
            read = backup.read,
            bookmark = backup.bookmark,
            lastPageRead = backup.progress,
        )
        restorer(database, deviceManga, chapters = listOf(dbChapter))
            .restore(BackupManga(source = 1L, url = "u", chapters = listOf(backupChapter)), emptyList())
        return result
    }

    override suspend fun track(device: TrackState, backup: TrackState): TrackState {
        var result = device
        val database = database {
            coEvery {
                manga_syncQueries.update(
                    any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                result = TrackState(arg(2), arg(3), arg(7), arg(8), arg(5))
                0L
            }
        }
        val dbTrack = Track(
            id = 1,
            mangaId = RestoreMergeConformanceTest.DEVICE_ID,
            trackerId = RestoreMergeConformanceTest.TRACKER_ID,
            remoteId = device.remoteId,
            libraryId = device.libraryId,
            title = "T",
            lastChapterRead = device.lastChapterRead,
            totalChapters = 0,
            status = device.status,
            score = device.score,
            remoteUrl = "",
            startDate = 0,
            finishDate = 0,
            private = false,
        )
        val backupTrack = BackupTracking(
            syncId = RestoreMergeConformanceTest.TRACKER_ID.toInt(),
            libraryId = backup.libraryId!!,
            title = "T",
            lastChapterRead = backup.lastChapterRead.toFloat(),
            score = backup.score.toFloat(),
            status = backup.status.toInt(),
            mediaId = backup.remoteId,
        )
        restorer(database, deviceManga, tracks = listOf(dbTrack))
            .restore(BackupManga(source = 1L, url = "u", tracking = listOf(backupTrack)), emptyList())
        return result
    }

    private fun database(block: Database.() -> Unit) = mockk<Database>(relaxed = true) {
        coEvery { transaction(any(), any()) } coAnswers {
            secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>().invoke(mockk(relaxed = true))
        }
        block()
    }

    private fun restorer(
        database: Database,
        dbManga: Manga,
        chapters: List<Chapter> = emptyList(),
        tracks: List<Track> = emptyList(),
    ) = MangaRestorer(
        database = database,
        getCategories = mockk { coEvery { await() } returns emptyList() },
        getMangaByUrlAndSourceId = mockk { coEvery { await("u", 1L) } returns dbManga },
        getChaptersByMangaId = mockk { coEvery { await(dbManga.id) } returns chapters },
        updateManga = mockk(relaxed = true),
        getTracks = mockk { coEvery { await(dbManga.id) } returns tracks },
        insertTrack = mockk(relaxed = true),
        fetchInterval = mockk(relaxed = true),
        restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
        mangaMetadataRepository = mockk(relaxed = true),
        setCustomMangaInfo = mockk(relaxed = true),
    )
}

class NovelMergeRestorer : MergeRestorer {

    override fun toString() = "novel"

    private val deviceNovel = Novel.create().copy(id = RestoreMergeConformanceTest.DEVICE_ID, url = "u", source = "s")

    override suspend fun description(deviceVersion: Long, backupVersion: Long): String? {
        var written: String? = null
        val novels = novels(deviceNovel.copy(description = "device", version = deviceVersion)) {
            coEvery { update(any<Novel>(), any()) } coAnswers {
                written = firstArg<Novel>().description
                true
            }
        }
        restorer(novels).restore(
            BackupNovel(source = "s", url = "u", description = "backup", version = backupVersion),
            emptyList(),
        )
        return written
    }

    override suspend fun chapter(device: ChapterState, backup: ChapterState): ChapterState {
        var result = device
        val dbChapter = NovelChapter(
            id = 1,
            novelId = RestoreMergeConformanceTest.DEVICE_ID,
            url = "c",
            name = "C",
            read = device.read,
            bookmark = device.bookmark,
            lastTextProgress = device.progress,
            chapterNumber = 0.0,
            sourceOrder = 0,
            dateFetch = 0,
            dateUpload = 0,
            page = "",
        )
        val chapters = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(RestoreMergeConformanceTest.DEVICE_ID) } returns listOf(dbChapter)
            coEvery { update(any()) } coAnswers {
                val chapter = firstArg<NovelChapter>()
                result = ChapterState(chapter.read, chapter.bookmark, chapter.lastTextProgress)
                true
            }
        }
        val backupChapter = BackupNovelChapter(
            url = "c",
            name = "C",
            read = backup.read,
            bookmark = backup.bookmark,
            lastTextProgress = backup.progress,
        )
        restorer(novels(deviceNovel), chapters = chapters)
            .restore(BackupNovel(source = "s", url = "u", chapters = listOf(backupChapter)), emptyList())
        return result
    }

    override suspend fun track(device: TrackState, backup: TrackState): TrackState {
        var result = device
        val dbTrack = NovelTrack(
            id = 1,
            novelId = RestoreMergeConformanceTest.DEVICE_ID,
            trackerId = RestoreMergeConformanceTest.TRACKER_ID,
            remoteId = device.remoteId,
            libraryId = device.libraryId,
            title = "T",
            lastChapterRead = device.lastChapterRead,
            totalChapters = 0,
            status = device.status,
            score = device.score,
            remoteUrl = "",
            startDate = 0,
            finishDate = 0,
            private = false,
        )
        val tracks = mockk<NovelTrackRepository>(relaxed = true) {
            coEvery { getTracksByNovelId(RestoreMergeConformanceTest.DEVICE_ID) } returns listOf(dbTrack)
            coEvery { insert(any()) } coAnswers {
                val track = firstArg<NovelTrack>()
                result = TrackState(track.remoteId, track.libraryId, track.status, track.score, track.lastChapterRead)
                true
            }
        }
        val backupTrack = BackupNovelTracking(
            trackerId = RestoreMergeConformanceTest.TRACKER_ID,
            remoteId = backup.remoteId,
            libraryId = backup.libraryId,
            title = "T",
            lastChapterRead = backup.lastChapterRead,
            status = backup.status,
            score = backup.score,
        )
        restorer(novels(deviceNovel), tracks = tracks)
            .restore(BackupNovel(source = "s", url = "u", tracking = listOf(backupTrack)), emptyList())
        return result
    }

    private fun novels(dbNovel: Novel, block: NovelRepository.() -> Unit = {}) =
        mockk<NovelRepository>(relaxed = true) {
            coEvery { getByUrlAndSource("u", "s") } returns dbNovel
            coEvery { getById(RestoreMergeConformanceTest.DEVICE_ID) } returns null
            coEvery { update(any<Novel>(), any()) } returns true
            block()
        }

    private fun restorer(
        novels: NovelRepository,
        chapters: NovelChapterRepository = mockk(relaxed = true),
        tracks: NovelTrackRepository = mockk(relaxed = true),
    ) = NovelRestorer(
        novelRepository = novels,
        novelChapterRepository = chapters,
        categoryRepository = mockk(relaxed = true),
        novelTrackRepository = tracks,
        restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
        setCustomNovelInfo = mockk(relaxed = true),
        database = mockk(relaxed = true),
    )
}
