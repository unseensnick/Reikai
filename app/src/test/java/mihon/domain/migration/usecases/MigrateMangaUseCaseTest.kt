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
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Test
import reikai.domain.db.PassThroughTransactions
import reikai.domain.db.Transactions
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
import tachiyomi.domain.track.model.Track
import java.io.File

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
        transactions: Transactions = PassThroughTransactions,
        downloadManager: DownloadManager = mockk(relaxed = true),
        getTracks: GetTracks = mockk { coEvery { await(any<Long>()) } returns emptyList() },
        insertTrack: InsertTrack = mockk(relaxed = true),
    ) = MigrateMangaUseCase(
        sourcePreferences = mockk(relaxed = true),
        trackerManager = mockk(relaxed = true) { every { trackers } returns emptyList() },
        sourceManager = sourceManager,
        downloadManager = downloadManager,
        updateManga = updateManga,
        getChaptersByMangaId = getChaptersByMangaId,
        updateChapter = mockk<UpdateChapter>(relaxed = true),
        getCategories = mockk { coEvery { await(any<Long>()) } returns emptyList() },
        setMangaCategories = mockk<SetMangaCategories>(relaxed = true),
        getTracks = getTracks,
        insertTrack = insertTrack,
        coverCache = coverCache,
        updateMangaFromRemote = mockk<UpdateMangaFromRemote>(relaxed = true),
        mangaMergeManager = mangaMergeManager,
        chapterRepository = chapterRepository,
        transactions = transactions,
    )

    /** Observes where the engine put its writes: a pass-through fake cannot tell the difference
     *  between two transactions and one, which is the whole contract here. */
    private class RecordingTransactions : Transactions {
        var entered = 0
        var completed = 0
        var inside = false

        override suspend fun <T> run(block: suspend () -> T): T {
            entered++
            inside = true
            try {
                return block().also { completed++ }
            } finally {
                inside = false
            }
        }
    }

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
    fun `a copy onto a merged entry adds the target to the group`() = runTest {
        val merge = mockk<MangaMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(any()) } returns longArrayOf(1L, 7L)
        }

        useCase(mangaMergeManager = merge)(
            manga(1),
            manga(2),
            replace = false,
            flags = emptySet(),
            skipTargetRefresh = true,
        )

        // The copy keeps the source in the library, so the group gains a member rather than swapping
        // one: every existing member plus the target. The novel engine pins both halves of this fork;
        // the manga half had no test, and every case that reached it stubbed the group away.
        coVerify(exactly = 1) { merge.merge(listOf(1L, 7L, 2L)) }
        coVerify(exactly = 0) { merge.replaceInGroup(any(), any()) }
    }

    @Test
    fun `a copy leaves an ungrouped entry ungrouped`() = runTest {
        val merge = mockk<MangaMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(any()) } returns longArrayOf(1L)
        }

        useCase(mangaMergeManager = merge)(
            manga(1),
            manga(2),
            replace = false,
            flags = emptySet(),
            skipTargetRefresh = true,
        )

        // A lone entry is its own "group" of one; copying it must not invent a real one.
        coVerify(exactly = 0) { merge.merge(any()) }
    }

    // The twins of MigrateNovelUseCaseTest's flag-carry cases. The engines are deliberately separate,
    // so a behaviour pinned on one side and not the other is where they drift; the cover-timestamp
    // bump below is an RK fix that had no test at all.

    @Test
    fun `cover flag copies the custom cover onto the target and bumps its timestamp`() = runTest {
        val src = File.createTempFile("mig-src", ".0").apply {
            writeText("COVER-BYTES")
            deleteOnExit()
        }
        val covers = mockk<CoverCache>(relaxed = true) {
            every { getCustomCoverFile(1L) } returns src
        }
        val update = mockk<UpdateManga>(relaxed = true) {
            coEvery { awaitAll(any<List<MangaUpdate>>()) } returns true
        }

        useCase(updateManga = update, coverCache = covers)(
            manga(1).copy(thumbnailUrl = "custom"),
            manga(2),
            replace = false,
            flags = setOf(MigrationFlag.CUSTOM_COVER),
            skipTargetRefresh = true,
        )

        // Without the timestamp bump a target that already had a custom cover keeps showing the old
        // one: the file changes but Coil's cache key does not.
        coVerify { covers.setCustomCoverToCache(any(), any()) }
        coVerify { update.awaitUpdateCoverLastModified(2L) }
    }

    @Test
    fun `notes flag carries the source notes onto the target`() = runTest {
        val updates = slot<List<MangaUpdate>>()
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(capture(updates)) } returns true }

        useCase(updateManga = update)(
            manga(1).copy(notes = "my note"),
            manga(2),
            replace = false,
            flags = setOf(MigrationFlag.NOTES),
            skipTargetRefresh = true,
        )

        capturedTargetUpdate(updates).notes shouldBe "my note"
    }

    @Test
    fun `without the notes flag the note is not carried`() = runTest {
        val updates = slot<List<MangaUpdate>>()
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(capture(updates)) } returns true }

        useCase(updateManga = update)(
            manga(1).copy(notes = "my note"),
            manga(2),
            replace = false,
            flags = emptySet(),
            skipTargetRefresh = true,
        )

        capturedTargetUpdate(updates).notes shouldBe null
    }

    @Test
    fun `migration carries the chapter and viewer flags onto the target`() = runTest {
        val updates = slot<List<MangaUpdate>>()
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(capture(updates)) } returns true }

        useCase(updateManga = update)(
            manga(1).copy(chapterFlags = 42L, viewerFlags = 7L),
            manga(2),
            replace = false,
            flags = emptySet(),
            skipTargetRefresh = true,
        )

        capturedTargetUpdate(updates).let {
            it.chapterFlags shouldBe 42L
            it.viewerFlags shouldBe 7L
        }
    }

    @Test
    fun `migration carries tracker links onto the target`() = runTest {
        val track = Track(
            id = 5L,
            mangaId = 1L,
            trackerId = 3L,
            remoteId = 9L,
            libraryId = null,
            title = "t",
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = 0L,
            finishDate = 0L,
            private = false,
        )
        val getTracks = mockk<GetTracks> { coEvery { await(1L) } returns listOf(track) }
        val insertTrack = mockk<InsertTrack>(relaxed = true)
        val inserted = slot<List<Track>>()
        coEvery { insertTrack.awaitAll(capture(inserted)) } returns Unit

        useCase(getTracks = getTracks, insertTrack = insertTrack)(
            manga(1),
            manga(2),
            replace = true,
            flags = emptySet(),
            skipTargetRefresh = true,
        )

        // Re-pointed at the target, not duplicated onto it: the row keeps its id so the tracker
        // binding moves rather than gaining a second copy.
        inserted.captured.single().let {
            it.mangaId shouldBe 2L
            it.id shouldBe 5L
        }
    }

    @Test
    fun `remove-download flag deletes the source's downloaded chapters`() = runTest {
        val source = mockk<Source>(relaxed = true)
        val downloads = mockk<DownloadManager>(relaxed = true)

        useCase(sourceManager = sourceManagerReturning(source), downloadManager = downloads)(
            manga(1),
            manga(2),
            replace = true,
            flags = setOf(MigrationFlag.REMOVE_DOWNLOAD),
            skipTargetRefresh = true,
        )

        coVerify { downloads.deleteManga(any(), source) }
    }

    @Test
    fun `migration never auto re-downloads chapters onto the target`() = runTest {
        val downloads = mockk<DownloadManager>(relaxed = true)

        useCase(downloadManager = downloads)(
            manga(1),
            manga(2),
            replace = true,
            flags = emptySet(),
            skipTargetRefresh = true,
        )

        // The novel engine pins this as an owner ruling (metered data); manga gets it by having no
        // download call at all, and this is what keeps it that way.
        coVerify(exactly = 0) { downloads.downloadChapters(any(), any(), any()) }
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
    fun `the group rewrite and the favorite swap are one unit of work`() = runTest {
        val transactions = RecordingTransactions()
        var groupMovedInsideUnit = false
        var swapInsideUnit = false
        val merge = mockk<MangaMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(any()) } returns longArrayOf(1L, 3L)
            coEvery { replaceInGroup(any(), any()) } answers { groupMovedInsideUnit = transactions.inside }
        }
        val update = mockk<UpdateManga>(relaxed = true) {
            coEvery { awaitAll(any<List<MangaUpdate>>()) } answers {
                swapInsideUnit = transactions.inside
                true
            }
        }

        useCase(updateManga = update, mangaMergeManager = merge, transactions = transactions)(
            manga(1),
            manga(2),
            flags = emptySet(),
            replace = true,
            skipTargetRefresh = true,
        )

        // Two transactions with a suspension point between them let a cancelled batch commit the swap
        // and never reach the rewrite, leaving the source out of the library but still in the group,
        // feeding chapters into it while invisible there.
        groupMovedInsideUnit shouldBe true
        swapInsideUnit shouldBe true
        transactions.completed shouldBe 1
        // Ordered, because "both inside one unit" does not pin the half that matters: the swap has to
        // be LAST. Swap first and the departing member is already unfavorited when the group
        // dissolves, so the tracker hand-out skips it and the surviving sibling loses the shared
        // binding, which is the loss this whole arrangement exists to prevent.
        coVerifyOrder {
            merge.replaceInGroup(1L, 2L)
            update.awaitAll(any<List<MangaUpdate>>())
        }
    }

    @Test
    fun `a failing swap aborts the unit that moved the merge group`() = runTest {
        val transactions = RecordingTransactions()
        val update = mockk<UpdateManga>(relaxed = true) { coEvery { awaitAll(any<List<MangaUpdate>>()) } returns false }
        val merge = mockk<MangaMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(any()) } returns longArrayOf(1L, 3L)
        }

        shouldThrow<IllegalStateException> {
            useCase(updateManga = update, mangaMergeManager = merge, transactions = transactions)(
                manga(1),
                manga(2),
                flags = emptySet(),
                replace = true,
                skipTargetRefresh = true,
            )
        }

        // The swap is last inside the unit, so its check throwing takes the group rewrite down with
        // it rather than leaving the group pointing at an entry that never entered the library.
        transactions.entered shouldBe 1
        transactions.completed shouldBe 0
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
