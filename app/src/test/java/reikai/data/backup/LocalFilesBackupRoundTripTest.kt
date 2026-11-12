package reikai.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.mangaRestorer
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterTombstoneRepositoryImpl
import reikai.data.chapter.LocalVolumeRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelHistoryRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.chapter.ChapterTombstone
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LooseFiles
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.local.VolumeOwner
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource

/**
 * A local series' own rows survive a reinstall, for both types: its volumes' saved order and names, the
 * volumes it lists from other folders (in the order they were written, which is the group's order), and its
 * removed chapters' kept states. Backed up from one database through the real creator and the wire format,
 * restored into another where the series gets a different id, so a row keyed by the old id would be lost.
 */
class LocalFilesBackupRoundTripTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a local series' volumes, owner rows and kept states come back onto its new id`(type: Type) = runTest {
        val before = Db.create()
        val after = Db.create()
        try {
            val volumes = LocalVolumeRepositoryImpl(before.database, backgroundScope)
            type.seedSeries(before.driver, id = 1, url = SERIES)
            volumes.setOrder(type.contentType, SERIES, listOf("vol2", "vol1"))
            volumes.setLabel(type.contentType, SERIES, "vol1", "First")
            volumes.restoreOwners(type.contentType, OWNERS + VolumeOwner("C", "x", "Elsewhere"))
            before.exec(
                "INSERT INTO local_volume_pick(content_type, entry_id, volume_key) VALUES (${type.db}, 1, 'vol2')",
            )
            type.tombstoneStatements(ownerId = 1).forEach { before.exec(it) }
            val keptBefore = ChapterTombstoneRepositoryImpl(before.database).getByOwners(type.contentType, listOf(1))[1]

            val wire = type.backUp(before.database, volumes)
            // The device the restore lands on already holds another series at id 1.
            type.seedSeries(after.driver, id = 1, url = "other")
            val restoredVolumes = LocalVolumeRepositoryImpl(after.database, backgroundScope)
            type.restore(after.database, wire, restoredVolumes)
            val newId = type.idOf(after.database, SERIES)

            Restored(
                newId = newId,
                volumes = restoredVolumes.loadedVolumes(type.contentType)[SERIES],
                owners = restoredVolumes.all(type.contentType),
                pick = restoredVolumes.loadedPicks(type.contentType)[newId],
                kept = ChapterTombstoneRepositoryImpl(
                    after.database,
                ).getByOwners(type.contentType, listOf(newId))[newId],
            ) shouldBe Restored(
                newId = 2,
                volumes = volumes.loadedVolumes(type.contentType)[SERIES],
                owners = OWNERS,
                pick = "vol2",
                kept = keptBefore,
            )
        } finally {
            before.driver.close()
            after.driver.close()
        }
    }

    // Manga only: in a local novel every book is a volume, so nothing is loose.
    @Test
    fun `a local manga's Loose files setting comes back`() = runTest {
        val before = Db.create()
        val after = Db.create()
        try {
            val volumes = LocalVolumeRepositoryImpl(before.database, backgroundScope)
            Type.MANGA.seedSeries(before.driver, id = 1, url = SERIES)
            volumes.setLooseFiles(SERIES, LooseFiles.EACH_ITS_OWN)

            val wire = Type.MANGA.backUp(before.database, volumes)
            val restored = LocalVolumeRepositoryImpl(after.database, backgroundScope)
            Type.MANGA.restore(after.database, wire, restored)

            restored.loadedLooseFiles() shouldBe mapOf(SERIES to LooseFiles.EACH_ITS_OWN)
        } finally {
            before.driver.close()
            after.driver.close()
        }
    }

    data class Restored(
        val newId: Long,
        val volumes: List<LocalVolume>?,
        val owners: List<VolumeOwner>,
        val pick: String?,
        val kept: List<ChapterTombstone>?,
    )

    class Db(val driver: JdbcSqliteDriver) {
        val database = DatabaseBindings.providesDatabase(driver)

        suspend fun exec(sql: String) {
            driver.execute(null, sql, 0).await()
        }

        companion object {
            suspend fun create() = Db(
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
                    Database.Schema.create(it).await()
                },
            )
        }
    }

    enum class Type(val contentType: ContentType, val db: Long) {
        MANGA(ContentType.MANGA, 0) {
            override suspend fun seedSeries(driver: JdbcSqliteDriver, id: Long, url: String) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                        "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($id, ${LocalSource.ID}, '$url', 'T', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                    0,
                ).await()
            }

            override fun tombstoneStatements(ownerId: Long) = listOf(
                "INSERT INTO manga_chapter_tombstone(manga_id, url, chapter_number, read, bookmark, last_page_read, " +
                    "page_count, date_fetch, history_read_at, history_read_duration, removed_at) " +
                    "VALUES ($ownerId, '$SERIES/vol1/c5.cbz', 5.0, 1, 1, 3, 20, 500, 200, 90, 7)",
                "INSERT INTO manga_chapter_tombstone_session(manga_id, url, ended_at, duration) " +
                    "VALUES ($ownerId, '$SERIES/vol1/c5.cbz', 2000, 40)",
            )

            override suspend fun backUp(database: Database, volumes: LocalVolumeRepositoryImpl): ByteArray {
                val entry = Manga.create().copy(id = 1, source = LocalSource.ID, url = SERIES, title = "T")
                val creator = MangaBackupCreator(
                    manualOrder = FakeLibraryManualOrderRepository(),
                    mangaRepository = mockk { coEvery { getExcludedScanlatorsByMangaIds(any()) } returns emptyMap() },
                    chapterRepository = ChapterRepositoryImpl(database),
                    trackRepository = mockk(),
                    categoryRepository = mockk(),
                    historyRepository = mockk(),
                    mangaMetadataRepository = mockk { coEvery { getMetadataById(any()) } returns null },
                    customMangaInfoRepository = mockk(),
                    getFavorites = mockk(),
                    getManga = mockk(),
                    mergeGroupRepository = mockk(),
                    chapterNumberOverrides = NoChapterNumberOverrides,
                    copyMarks = InMemoryChapterCopyMarks(),
                    localVolumes = volumes,
                    readingLog = NoReadingLog,
                    tombstones = ChapterTombstoneRepositoryImpl(database),
                )
                // The series' own fields first, then its chapters, as every backup writes them.
                val backup = creator.base(listOf(entry)).single()
                creator.chapters(listOf(entry to backup))
                return ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup)
            }

            override suspend fun restore(
                database: Database,
                wire: ByteArray,
                volumes: LocalVolumeRepositoryImpl,
            ) {
                val backup = ProtoBuf.decodeFromByteArray(BackupManga.serializer(), wire)
                mangaRestorer(database, localVolumes = volumes).restore(listOf(backup), emptyList())
            }

            override suspend fun idOf(database: Database, url: String) =
                MangaRepositoryImpl(database).getMangaByUrlAndSourceId(url, LocalSource.ID)!!.id
        },
        NOVEL(ContentType.NOVELS, 1) {
            override suspend fun seedSeries(driver: JdbcSqliteDriver, id: Long, url: String) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($id, '$LOCAL_NOVEL', '$url', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
            }

            override fun tombstoneStatements(ownerId: Long) = listOf(
                "INSERT INTO novel_chapter_tombstone(novel_id, url, chapter_number, read, bookmark, " +
                    "last_text_progress, words, listened_ms, date_fetch, history_last_read, history_time_read, " +
                    "removed_at) VALUES ($ownerId, '$SERIES/book.epub#5', 5.0, 1, 1, 3, 20, 5, 500, 200, 90, 7)",
                "INSERT INTO novel_chapter_tombstone_session(novel_id, url, ended_at, duration) " +
                    "VALUES ($ownerId, '$SERIES/book.epub#5', 2000, 40)",
            )

            override suspend fun backUp(database: Database, volumes: LocalVolumeRepositoryImpl): ByteArray {
                val entry = Novel.create().copy(id = 1, source = LOCAL_NOVEL, url = SERIES, title = "T")
                val creator = NovelBackupCreator(
                    manualOrder = FakeLibraryManualOrderRepository(),
                    novelRepository = mockk(),
                    novelChapterRepository = NovelChapterRepositoryImpl(database),
                    categoryRepository = mockk(),
                    novelTrackRepository = mockk(),
                    mergeGroupRepository = mockk(),
                    customNovelInfoRepository = mockk(),
                    novelHistoryRepository = mockk(),
                    novelSourceManager = mockk(),
                    chapterNumberOverrides = NoChapterNumberOverrides,
                    copyMarks = InMemoryChapterCopyMarks(),
                    localVolumes = volumes,
                    readingLog = NoReadingLog,
                    readingPace = NoReadingPace,
                    localNovelLanguages = NoLocalNovelLanguages,
                    tombstones = ChapterTombstoneRepositoryImpl(database),
                )
                val backup = creator.base(listOf(entry)).single()
                creator.chapters(listOf(entry to backup))
                return ProtoBuf.encodeToByteArray(BackupNovel.serializer(), backup)
            }

            override suspend fun restore(
                database: Database,
                wire: ByteArray,
                volumes: LocalVolumeRepositoryImpl,
            ) {
                NovelRestorer(
                    manualOrder = FakeLibraryManualOrderRepository(),
                    novelRepository = NovelRepositoryImpl(database),
                    novelChapterRepository = NovelChapterRepositoryImpl(database),
                    categoryRepository = mockk(relaxed = true),
                    novelTrackRepository = mockk(relaxed = true),
                    restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
                    setCustomNovelInfo = mockk(relaxed = true),
                    novelHistoryRepository = NovelHistoryRepositoryImpl(database),
                    chapterNumberOverrides = NoChapterNumberOverrides,
                    copyMarks = InMemoryChapterCopyMarks(),
                    localVolumes = volumes,
                    readingLog = NoReadingLog,
                    readingPace = NoReadingPace,
                    localNovelLanguages = NoLocalNovelLanguages,
                    tombstones = ChapterTombstoneRepositoryImpl(database),
                ).restore(ProtoBuf.decodeFromByteArray(BackupNovel.serializer(), wire), emptyList())
            }

            override suspend fun idOf(database: Database, url: String) =
                NovelRepositoryImpl(database).getByUrlAndSource(url, LOCAL_NOVEL)!!.id
        },
        ;

        abstract suspend fun seedSeries(driver: JdbcSqliteDriver, id: Long, url: String)
        abstract fun tombstoneStatements(ownerId: Long): List<String>
        abstract suspend fun backUp(database: Database, volumes: LocalVolumeRepositoryImpl): ByteArray
        abstract suspend fun restore(
            database: Database,
            wire: ByteArray,
            volumes: LocalVolumeRepositoryImpl,
        )
        abstract suspend fun idOf(database: Database, url: String): Long
    }

    companion object {
        private const val SERIES = "Series"
        private const val LOCAL_NOVEL = "local"

        // A split series' self row, then two volumes borrowed from other folders, in the group's order.
        private val OWNERS = listOf(
            VolumeOwner(SERIES, VolumeOwner.WHOLE, SERIES),
            VolumeOwner("B", "v3", SERIES),
            VolumeOwner("A", VolumeOwner.LOOSE, SERIES),
        )
    }
}
