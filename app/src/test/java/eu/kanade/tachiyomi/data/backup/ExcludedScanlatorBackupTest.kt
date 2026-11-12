package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.readinglog.NoReadingLog
import tachiyomi.domain.manga.model.Manga

/** The scanlators a user hid on a series come back with it, written by the creator and read by the restore. */
class ExcludedScanlatorBackupTest {

    @Test
    fun `a series' hidden scanlators survive a backup and restore`() = runTest {
        val wire = MangaRestoreHarness.create().use { device ->
            val id = device.insert(Manga.create().copy(url = "u", source = 1L, title = "T"))
            device.database.excluded_scanlatorQueries.insert(id, "X")
            device.database.excluded_scanlatorQueries.insert(id, "Y")
            val backup = creator(device).base(listOf(device.mangas.getMangaById(id))).single()
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup)
        }

        val restored = MangaRestoreHarness.create().use { fresh ->
            fresh.restorer().restore(listOf(ProtoBuf.decodeFromByteArray(BackupManga.serializer(), wire)), emptyList())
            val id = fresh.mangas.getMangaByUrlAndSourceId("u", 1L)!!.id
            fresh.mangas.getExcludedScanlatorsByMangaIds(listOf(id))[id].orEmpty().toSet()
        }

        restored shouldBe setOf("X", "Y")
    }

    private fun creator(harness: MangaRestoreHarness) = MangaBackupCreator(
        tombstones = NoChapterTombstoneRepository,
        manualOrder = FakeLibraryManualOrderRepository(),
        mangaRepository = harness.mangas,
        chapterRepository = harness.chapters,
        categoryRepository = harness.categories,
        historyRepository = mockk(),
        trackRepository = harness.tracks,
        mangaMetadataRepository = mockk { coEvery { getMetadataById(any()) } returns null },
        customMangaInfoRepository = mockk(),
        getFavorites = mockk(),
        getManga = mockk(),
        mergeGroupRepository = mockk(),
        chapterNumberOverrides = NoChapterNumberOverrides,
        copyMarks = InMemoryChapterCopyMarks(),
        localVolumes = NoLocalVolumes,
        volumePicks = NoVolumePicks,
        readingLog = NoReadingLog,
    )
}
