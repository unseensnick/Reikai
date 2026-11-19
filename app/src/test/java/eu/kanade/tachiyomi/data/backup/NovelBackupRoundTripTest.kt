package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.toBackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.library.FakeLibraryHiddenRepository
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import kotlin.time.Instant

class NovelBackupRoundTripTest {

    private fun novel(id: Long, url: String, source: String, title: String = "T") =
        Novel.create().copy(
            id = id,
            url = url,
            source = source,
            title = title,
            favoriteAt = Instant.fromEpochMilliseconds(0L),
        )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `a novel's reader flags, update strategy and cover date survive a backup and its restore`() {
        val stored = novel(1, "u", "s1").copy(
            viewerFlags = 0x15,
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            coverLastModified = Instant.fromEpochMilliseconds(9_000L),
        )

        val wire = ProtoBuf.encodeToByteArray(BackupNovel.serializer(), stored.toBackupNovel())
        val restored = ProtoBuf.decodeFromByteArray(BackupNovel.serializer(), wire).toNovelImpl()

        Triple(restored.viewerFlags, restored.updateStrategy, restored.coverLastModified) shouldBe
            Triple(0x15L, UpdateStrategy.ONLY_FETCH_ONCE, Instant.fromEpochMilliseconds(9_000L))
    }

    @Test
    fun `BackupNovel maps back to the domain novel`() {
        val backup = BackupNovel(
            source = "s1",
            url = "u",
            title = "Title",
            status = 2,
            thumbnailUrl = "cover",
            favorite = true,
            totalPages = 3,
            notes = "my note",
        )

        val novel = backup.toNovelImpl()

        novel.source shouldBe "s1"
        novel.url shouldBe "u"
        novel.title shouldBe "Title"
        novel.status shouldBe 2L
        novel.thumbnailUrl shouldBe "cover"
        novel.favorite shouldBe true
        novel.totalPages shouldBe 3L
        novel.notes shouldBe "my note"
    }

    @Test
    fun `BackupNovelChapter restores under the new novel id`() {
        // Downloaded state isn't in the backup or the model anymore; it's rederived from disk by
        // NovelDownloadCache after restore.
        val chapter = BackupNovelChapter(url = "c1", name = "Chapter 1", read = true, lastTextProgress = 4200)
            .toChapterImpl(novelId = 42)

        chapter.novelId shouldBe 42L
        chapter.url shouldBe "c1"
        chapter.read shouldBe true
        chapter.lastTextProgress shouldBe 4200L
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `a chapter's group survives a backup and its restore`() = runTest {
        val stored = NovelChapter(
            id = 7L, novelId = 1L, url = "c1", name = "Chapter 1", read = false, bookmark = false,
            lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L,
            dateFetch = Instant.fromEpochMilliseconds(
                0L,
            ),
            dateUpload = null,
            page = "", scanlator = "Group",
        )
        val creator = NovelBackupCreator(
            libraryHides = FakeLibraryHiddenRepository(),
            tombstones = NoChapterTombstoneRepository,
            manualOrder = FakeLibraryManualOrderRepository(),
            novelRepository = mockk(),
            novelChapterRepository = mockk {
                coEvery { getByNovelIds(listOf(1L)) } returns mapOf(1L to listOf(stored))
            },
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
        val backup = BackupNovel(source = "s1", url = "u")
        creator.chapters(listOf(novel(1, "u", "s1") to backup))

        val wire = ProtoBuf.encodeToByteArray(BackupNovelChapter.serializer(), backup.chapters.single())

        ProtoBuf.decodeFromByteArray(BackupNovelChapter.serializer(), wire).toChapterImpl(novelId = 42)
            .scanlator shouldBe "Group"
    }
}
