package mihon.domain.migration.usecases

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Test
import reikai.domain.manga.MangaMergeManager
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

/**
 * The manga migration engine's safety contracts, the twins of MigrateNovelUseCaseTest's: the
 * favorite swap is one checked write placed last, the chapter carry is checked, and the engine
 * refuses a no-op rather than reporting a migration that did nothing.
 */
class MigrateMangaUseCaseTest {

    private fun manga(id: Long, source: Long = 1L) = Manga.create().copy(id = id, source = source, url = "/$id")

    /** Stubbed outside the mockk block: a bare `get(any())` inside one binds to MockK's own get. */
    private fun sourceManagerReturning(source: Source?) = mockk<SourceManager>().also {
        every { it.get(any()) } returns source
    }

    private fun useCase(
        updateManga: UpdateManga =
            mockk(relaxed = true) { coEvery { awaitAll(any<List<MangaUpdate>>()) } returns true },
        sourceManager: SourceManager = sourceManagerReturning(mockk<Source>(relaxed = true)),
        chapterRepository: ChapterRepository = mockk(relaxed = true),
        getChaptersByMangaId: GetChaptersByMangaId = mockk { coEvery { await(any()) } returns emptyList() },
        mangaMergeManager: MangaMergeManager = mockk(relaxed = true) {
            coEvery { computeRelatedIds(any()) } returns longArrayOf()
        },
        coverCache: CoverCache = mockk(relaxed = true),
    ) = MigrateMangaUseCase(
        sourcePreferences = mockk(relaxed = true),
        trackerManager = mockk(relaxed = true) { every { trackers } returns emptyList() },
        sourceManager = sourceManager,
        downloadManager = mockk<DownloadManager>(relaxed = true),
        updateManga = updateManga,
        getChaptersByMangaId = getChaptersByMangaId,
        updateChapter = mockk<UpdateChapter>(relaxed = true),
        getCategories = mockk { coEvery { await(any<Long>()) } returns emptyList() },
        setMangaCategories = mockk<SetMangaCategories>(relaxed = true),
        getTracks = mockk { coEvery { await(any<Long>()) } returns emptyList() },
        insertTrack = mockk<InsertTrack>(relaxed = true),
        coverCache = coverCache,
        updateMangaFromRemote = mockk<UpdateMangaFromRemote>(relaxed = true),
        mangaMergeManager = mangaMergeManager,
        chapterRepository = chapterRepository,
    )

    /** The target's MangaUpdate out of the batched favorite swap. */
    private fun capturedTargetUpdate(updates: CapturingSlot<List<MangaUpdate>>): MangaUpdate =
        updates.captured.single { it.favorite == true }

    @Test
    fun `a replace swaps both entries in one write, so neither can be left out of the library`() = runTest {
        val updates = slot<List<MangaUpdate>>()
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(capture(updates)) } returns true }

        useCase(updateManga = update)(manga(1), manga(2), replace = true, flags = emptySet(), skipTargetRefresh = true)

        coVerify(exactly = 1) { update.awaitAll(any<List<MangaUpdate>>()) }
        updates.captured.size shouldBe 2
        updates.captured.single { it.id == 1L }.let {
            it.favorite shouldBe false
            it.dateAdded shouldBe 0
        }
        capturedTargetUpdate(updates).id shouldBe 2L
    }

    @Test
    fun `a copy leaves the source favorited`() = runTest {
        val updates = slot<List<MangaUpdate>>()
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(capture(updates)) } returns true }

        useCase(updateManga = update)(manga(1), manga(2), replace = false, flags = emptySet(), skipTargetRefresh = true)

        updates.captured.map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `a failed favorite swap fails the migration instead of passing silently`() = runTest {
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(any<List<MangaUpdate>>()) } returns false }

        shouldThrow<IllegalStateException> {
            useCase(
                updateManga = update,
            )(manga(1), manga(2), replace = true, flags = emptySet(), skipTargetRefresh = true)
        }
    }

    @Test
    fun `the merge group is only touched after the swap has succeeded`() = runTest {
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(any<List<MangaUpdate>>()) } returns false }
        val merge = mockk<MangaMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(any()) } returns longArrayOf(1L, 3L)
        }

        shouldThrow<IllegalStateException> {
            useCase(updateManga = update, mangaMergeManager = merge)(
                manga(1),
                manga(2),
                flags = emptySet(),
                replace = true,
                skipTargetRefresh = true,
            )
        }

        coVerify(exactly = 0) { merge.replaceInGroup(any(), any()) }
    }

    @Test
    fun `a failing chapter carry fails the row rather than reporting a half carry`() = runTest {
        val chapters = mockk<ChapterRepository> {
            coEvery { updateAll(any<List<ChapterUpdate>>()) } throws IllegalStateException("db gone")
        }
        val current = mockk<GetChaptersByMangaId> { coEvery { await(any()) } returns emptyList() }

        shouldThrow<IllegalStateException> {
            useCase(chapterRepository = chapters, getChaptersByMangaId = current)(
                manga(1),
                manga(2),
                flags = setOf(MigrationFlag.CHAPTER),
                replace = true,
                skipTargetRefresh = true,
            )
        }
    }

    @Test
    fun `migrating an entry onto itself does nothing at all`() = runTest {
        val update = mockk<UpdateManga>(relaxed = true)

        useCase(updateManga = update)(manga(1), manga(1), replace = true, flags = emptySet(), skipTargetRefresh = true)

        coVerify(exactly = 0) { update.awaitAll(any<List<MangaUpdate>>()) }
    }

    @Test
    fun `a missing target source fails the row instead of returning as if migrated`() = runTest {
        shouldThrow<IllegalStateException> {
            useCase(sourceManager = sourceManagerReturning(null))(
                manga(1),
                manga(2),
                flags = emptySet(),
                replace = true,
                skipTargetRefresh = true,
            )
        }
    }
}
