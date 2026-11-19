package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.entry.EntryId
import reikai.domain.library.FakeLibraryHiddenRepository
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.domain.manga.model.Manga

/**
 * Each type's backup creator writes an entry's hide on field 708 and the file carries it back: the hidden
 * entry decodes hidden and the other one does not. The restore side is HiddenRestoreConformanceTest.
 */
class HiddenEntryBackupWireTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a hidden entry's backup decodes hidden, the others visible`(type: Type) = runTest {
        type.backUpAndDecode(hidden = 1L, entries = listOf(1L, 2L)) shouldBe listOf(1L to true, 2L to false)
    }

    enum class Type {
        MANGA {
            override suspend fun backUpAndDecode(hidden: Long, entries: List<Long>): List<Pair<Long, Boolean>> {
                val creator = MangaBackupCreator(
                    libraryHides = FakeLibraryHiddenRepository(setOf(EntryId.Manga(hidden))),
                    tombstones = NoChapterTombstoneRepository,
                    manualOrder = FakeLibraryManualOrderRepository(),
                    mangaRepository = mockk { coEvery { getExcludedScanlatorsByMangaIds(any()) } returns emptyMap() },
                    chapterRepository = mockk(),
                    categoryRepository = mockk(),
                    historyRepository = mockk(),
                    trackRepository = mockk(),
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
                val manga = entries.map { Manga.create().copy(id = it, url = "m$it", source = 1L) }
                return creator.base(manga).map { backup ->
                    val wire = ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup)
                    val decoded = ProtoBuf.decodeFromByteArray(BackupManga.serializer(), wire)
                    decoded.url.removePrefix("m").toLong() to decoded.libraryHidden
                }
            }
        },
        NOVEL {
            override suspend fun backUpAndDecode(hidden: Long, entries: List<Long>): List<Pair<Long, Boolean>> {
                val creator = NovelBackupCreator(
                    libraryHides = FakeLibraryHiddenRepository(setOf(EntryId.Novel(hidden))),
                    tombstones = NoChapterTombstoneRepository,
                    manualOrder = FakeLibraryManualOrderRepository(),
                    novelRepository = mockk(),
                    novelChapterRepository = mockk(),
                    categoryRepository = mockk(),
                    novelTrackRepository = mockk(),
                    mergeGroupRepository = mockk(),
                    customNovelInfoRepository = mockk(),
                    novelHistoryRepository = mockk(),
                    novelSourceManager = mockk(),
                    chapterNumberOverrides = NoChapterNumberOverrides,
                    copyMarks = InMemoryChapterCopyMarks(),
                    localVolumes = NoLocalVolumes,
                    volumePicks = NoVolumePicks,
                    readingLog = NoReadingLog,
                    readingPace = NoReadingPace,
                    localNovelLanguages = NoLocalNovelLanguages,
                )
                val novels = entries.map { Novel.create().copy(id = it, url = "n$it", source = "src") }
                return creator.base(novels).map { backup ->
                    val wire = ProtoBuf.encodeToByteArray(BackupNovel.serializer(), backup)
                    val decoded = ProtoBuf.decodeFromByteArray(BackupNovel.serializer(), wire)
                    decoded.url.removePrefix("n").toLong() to decoded.libraryHidden
                }
            }
        },
        ;

        abstract suspend fun backUpAndDecode(hidden: Long, entries: List<Long>): List<Pair<Long, Boolean>>
    }
}
