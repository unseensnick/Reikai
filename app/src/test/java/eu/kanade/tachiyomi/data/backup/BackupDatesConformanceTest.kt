package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.NovelTrackRepositoryImpl
import reikai.domain.backup.RestoredChapterHistory
import reikai.domain.chapter.ChapterCopyMark
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.FakeLibraryHiddenRepository
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.library.StoredType
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import kotlin.time.Instant

/**
 * The backup format keeps writing 0 for a date nobody knows, so a backup made here reads the same in an
 * older build and in Mihon, and a 0 from any backup restores as unknown. Each type's own creator parts and
 * restore mappers, through the real wire encoding.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupDatesConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `known dates survive a backup and a restore`(type: Type) = runTest {
        type.restore(type.backUp(KNOWN)) shouldBe KNOWN.copy(readAtOnWire = KNOWN_READ_AT)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `unknown dates are written as 0 and restore as unknown`(type: Type) = runTest {
        type.restore(type.backUp(UNKNOWN)) shouldBe UNKNOWN.copy(readAtOnWire = 0L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter's duplicate mark survives a backup and a restore`(type: Type) = runTest {
        val marks = InMemoryChapterCopyMarks().apply {
            set(type.contentType, listOf(ChapterCopyMark(ID, CHAPTER_URL, CopyMark.NeverSkip)))
        }

        type.restoredMarks(type.backUp(KNOWN, marks)) shouldBe mapOf(CHAPTER_URL to CopyMark.NeverSkip)
    }

    /** The dates a backup carries; [readAtOnWire] is the history time as written, which a restore merges. */
    data class Dates(
        val favoriteAt: Instant?,
        val dateFetch: Instant,
        val dateUpload: Instant?,
        val startDate: Instant?,
        val finishDate: Instant?,
        val readAt: Instant?,
        val nextUpdate: Instant?,
        val readAtOnWire: Long? = null,
    )

    enum class Type(val contentType: StoredType) {
        MANGA(StoredType.MANGA) {
            override suspend fun backUp(dates: Dates, marks: ChapterCopyMarkRepository): ByteArray {
                val manga = Manga.create().copy(
                    id = ID,
                    source = 1L,
                    url = "/m",
                    favoriteAt = dates.favoriteAt,
                    nextUpdate = dates.nextUpdate,
                )
                val chapter = Chapter.create().copy(
                    id = CHAPTER_ID,
                    mangaId = ID,
                    url = CHAPTER_URL,
                    dateFetch = dates.dateFetch,
                    dateUpload = dates.dateUpload,
                )
                val track = Track(
                    id = 1L, mangaId = ID, trackerId = 2L, remoteId = 3L, libraryId = null, title = "T",
                    lastChapterRead = 0.0, totalChapters = 0L, status = 1L, score = 0.0, remoteUrl = "",
                    startDate = dates.startDate, finishDate = dates.finishDate, private = false,
                )
                val creator = MangaBackupCreator(
                    libraryHides = FakeLibraryHiddenRepository(),
                    tombstones = NoChapterTombstoneRepository,
                    manualOrder = FakeLibraryManualOrderRepository(),
                    mangaRepository = mockk {
                        coEvery { getExcludedScanlatorsByMangaIds(any()) } returns emptyMap()
                    },
                    chapterRepository = mockk {
                        coEvery { getChaptersByMangaIds(any()) } returns mapOf(ID to listOf(chapter))
                    },
                    categoryRepository = mockk(relaxed = true),
                    historyRepository = mockk {
                        coEvery { getHistoryByMangaIds(any()) } returns
                            mapOf(ID to listOf(History(1L, CHAPTER_ID, dates.readAt, readDuration = 5L)))
                    },
                    trackRepository = mockk {
                        coEvery { getTracksByMangaIds(any()) } returns mapOf(ID to listOf(track))
                    },
                    mangaMetadataRepository = mockk { coEvery { getMetadataById(any()) } returns null },
                    customMangaInfoRepository = mockk(relaxed = true),
                    getFavorites = mockk(relaxed = true),
                    getManga = mockk(relaxed = true),
                    mergeGroupRepository = mockk(relaxed = true),
                    chapterNumberOverrides = NoChapterNumberOverrides,
                    copyMarks = marks,
                    localVolumes = NoLocalVolumes,
                    volumePicks = NoVolumePicks,
                    readingLog = NoReadingLog,
                )
                val batch = listOf(manga to creator.base(listOf(manga)).single())
                creator.chapters(batch)
                creator.tracking(batch)
                creator.history(batch)
                return ProtoBuf.encodeToByteArray(BackupManga.serializer(), batch.single().second)
            }

            override fun restore(wire: ByteArray): Dates {
                val backup = ProtoBuf.decodeFromByteArray(BackupManga.serializer(), wire)
                val chapter = backup.chapters.single().toChapterImpl()
                val track = backup.tracking.single().getTrackImpl()
                return Dates(
                    favoriteAt = backup.getMangaImpl().favoriteAt,
                    dateFetch = chapter.dateFetch,
                    dateUpload = chapter.dateUpload,
                    startDate = track.startDate,
                    finishDate = track.finishDate,
                    readAt = backup.history.single().getHistoryImpl().readAt,
                    nextUpdate = backup.getMangaImpl().nextUpdate,
                    readAtOnWire = backup.history.single().lastRead,
                )
            }

            override suspend fun restoredMarks(wire: ByteArray): Map<String, CopyMark> =
                MangaRestoreHarness.create().use { harness ->
                    val marks = InMemoryChapterCopyMarks()
                    mangaRestorer(harness.database, copyMarks = marks)
                        .restore(listOf(ProtoBuf.decodeFromByteArray(BackupManga.serializer(), wire)), emptyList())
                    marks.loadedMarks(StoredType.MANGA).values.flatMap { it.entries }.associate { it.key to it.value }
                }
        },
        NOVEL(StoredType.NOVELS) {
            override suspend fun backUp(dates: Dates, marks: ChapterCopyMarkRepository): ByteArray {
                val novel = Novel.create().copy(
                    id = ID,
                    source = "s",
                    url = "/n",
                    favoriteAt = dates.favoriteAt,
                    nextUpdate = dates.nextUpdate,
                )
                val chapter = NovelChapter(
                    id = CHAPTER_ID, novelId = ID, url = CHAPTER_URL, name = "c", read = false, bookmark = false,
                    lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = dates.dateFetch,
                    dateUpload = dates.dateUpload, page = "",
                )
                val track = NovelTrack(
                    id = 1L, novelId = ID, trackerId = 2L, remoteId = 3L, libraryId = null, title = "T",
                    lastChapterRead = 0.0, totalChapters = 0L, status = 1L, score = 0.0, remoteUrl = "",
                    startDate = dates.startDate, finishDate = dates.finishDate, private = false,
                )
                val creator = NovelBackupCreator(
                    libraryHides = FakeLibraryHiddenRepository(),
                    tombstones = NoChapterTombstoneRepository,
                    manualOrder = FakeLibraryManualOrderRepository(),
                    novelRepository = mockk(relaxed = true),
                    novelChapterRepository = mockk {
                        coEvery { getByNovelIds(any()) } returns
                            mapOf(ID to listOf(chapter))
                    },
                    categoryRepository = mockk(relaxed = true),
                    novelTrackRepository = mockk {
                        coEvery { getTracksByNovelIds(any()) } returns mapOf(ID to listOf(track))
                    },
                    mergeGroupRepository = mockk(relaxed = true),
                    customNovelInfoRepository = mockk(relaxed = true),
                    novelHistoryRepository = mockk {
                        coEvery { getUrlKeyedHistoryByNovelIds(any()) } returns
                            mapOf(ID to listOf(RestoredChapterHistory(CHAPTER_URL, dates.readAt, readDuration = 5L)))
                    },
                    novelSourceManager = mockk(),
                    chapterNumberOverrides = NoChapterNumberOverrides,
                    copyMarks = marks,
                    localVolumes = NoLocalVolumes,
                    volumePicks = NoVolumePicks,
                    readingLog = NoReadingLog,
                    readingPace = NoReadingPace,
                    localNovelLanguages = NoLocalNovelLanguages,
                )
                val batch = listOf(novel to creator.base(listOf(novel)).single())
                creator.chapters(batch)
                creator.tracking(batch)
                creator.history(batch)
                return ProtoBuf.encodeToByteArray(BackupNovel.serializer(), batch.single().second)
            }

            override fun restore(wire: ByteArray): Dates {
                val backup = ProtoBuf.decodeFromByteArray(BackupNovel.serializer(), wire)
                val chapter = backup.chapters.single().toChapterImpl(ID)
                val track = backup.tracking.single().toTrackImpl(ID)
                return Dates(
                    favoriteAt = backup.toNovelImpl().favoriteAt,
                    dateFetch = chapter.dateFetch,
                    dateUpload = chapter.dateUpload,
                    startDate = track.startDate,
                    finishDate = track.finishDate,
                    readAt = backup.history.single().toRestoredHistory().readAt,
                    nextUpdate = backup.toNovelImpl().nextUpdate,
                    readAtOnWire = backup.history.single().lastRead,
                )
            }

            override suspend fun restoredMarks(wire: ByteArray): Map<String, CopyMark> {
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                Database.Schema.create(driver).await()
                val database = DatabaseBindings.providesDatabase(driver)
                val marks = InMemoryChapterCopyMarks()
                try {
                    novelRestorer(database, marks)
                        .restore(ProtoBuf.decodeFromByteArray(BackupNovel.serializer(), wire), emptyList())
                } finally {
                    driver.close()
                }
                return marks.loadedMarks(StoredType.NOVELS).values.flatMap { it.entries }.associate {
                    it.key to
                        it.value
                }
            }
        },
        ;

        abstract suspend fun backUp(
            dates: Dates,
            marks: ChapterCopyMarkRepository = InMemoryChapterCopyMarks(),
        ): ByteArray

        abstract fun restore(wire: ByteArray): Dates

        /** The duplicate marks the type's real restorer stores from the wire. */
        abstract suspend fun restoredMarks(wire: ByteArray): Map<String, CopyMark>
    }

    private companion object {
        const val ID = 1L
        const val CHAPTER_ID = 10L
        const val CHAPTER_URL = "/c/1"
        const val KNOWN_READ_AT = 6_000L

        val KNOWN = Dates(
            favoriteAt = Instant.fromEpochMilliseconds(1_000L),
            dateFetch = Instant.fromEpochMilliseconds(2_000L),
            dateUpload = Instant.fromEpochMilliseconds(3_000L),
            startDate = Instant.fromEpochMilliseconds(4_000L),
            finishDate = Instant.fromEpochMilliseconds(5_000L),
            readAt = Instant.fromEpochMilliseconds(KNOWN_READ_AT),
            nextUpdate = Instant.fromEpochMilliseconds(7_000L),
        )

        // In the library with no recorded add date, and every other date unknown
        val UNKNOWN = Dates(
            favoriteAt = Manga.UNKNOWN_FAVORITE_AT,
            dateFetch = Instant.fromEpochMilliseconds(2_000L),
            dateUpload = null,
            startDate = null,
            finishDate = null,
            readAt = null,
            nextUpdate = null,
        )
    }
}

/** The novel restore over [database]'s novels, chapters and history, with [copyMarks] to read back. */
private fun novelRestorer(database: Database, copyMarks: ChapterCopyMarkRepository) = NovelRestorer(
    tombstones = NoChapterTombstoneRepository,
    manualOrder = FakeLibraryManualOrderRepository(),
    novelRepository = NovelRepositoryImpl(database),
    novelChapterRepository = NovelChapterRepositoryImpl(database),
    categoryRepository = mockk(relaxed = true),
    novelTrackRepository = NovelTrackRepositoryImpl(database),
    restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
    setCustomNovelInfo = mockk(relaxed = true),
    novelHistoryRepository = NovelHistoryRepositoryImpl(database),
    chapterNumberOverrides = NoChapterNumberOverrides,
    copyMarks = copyMarks,
    localVolumes = NoLocalVolumes,
    volumePicks = NoVolumePicks,
    readingLog = NoReadingLog,
    readingPace = NoReadingPace,
    localNovelLanguages = NoLocalNovelLanguages,
)
