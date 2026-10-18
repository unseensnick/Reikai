package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.common.extensions.toInstantOrNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.backup.RestoredChapterHistory
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import reikai.domain.readinglog.NoReadingLog
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

    enum class Type {
        MANGA {
            override suspend fun backUp(dates: Dates): ByteArray {
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
        },
        NOVEL {
            override suspend fun backUp(dates: Dates): ByteArray {
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
                    readingLog = NoReadingLog,
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
                    // NovelRestorer reads history's time inside its merge, by this same call
                    readAt = backup.history.single().lastRead.toInstantOrNull(),
                    nextUpdate = backup.toNovelImpl().nextUpdate,
                    readAtOnWire = backup.history.single().lastRead,
                )
            }
        },
        ;

        abstract suspend fun backUp(dates: Dates): ByteArray

        abstract fun restore(wire: ByteArray): Dates
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
