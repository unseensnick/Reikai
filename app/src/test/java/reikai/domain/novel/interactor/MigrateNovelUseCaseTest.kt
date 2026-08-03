package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager
import java.io.File

class MigrateNovelUseCaseTest {

    private fun novel(id: Long, notes: String = "") = Novel.create().copy(id = id, notes = notes)

    private fun novelTrack(novelId: Long) = NovelTrack(
        id = 0, novelId = novelId, trackerId = 1, remoteId = 1, libraryId = null, title = "t",
        lastChapterRead = 0.0, totalChapters = 0, status = 0, score = 0.0, remoteUrl = "",
        startDate = 0, finishDate = 0, private = false,
    )

    // Relaxed mockk can't synthesize a primitive LongArray return, so stub the always-called resolver
    // to an empty group (no merge) by default; merge tests override it.
    private fun defaultMerge() = mockk<NovelMergeManager>(relaxed = true) {
        coEvery { computeRelatedIds(any()) } returns longArrayOf()
    }

    // A relaxed Boolean mock answers false, which the engine's check() treats as failure, so the two
    // batched writes default to success here; failure-contract tests override them.
    private fun defaultNovelRepo() = mockk<NovelRepository>(relaxed = true) {
        coEvery { updateAll(any()) } returns true
    }

    private fun useCase(
        coverCache: CoverCache = mockk(relaxed = true),
        updateNovel: UpdateNovel = mockk(relaxed = true),
        novelMergeManager: NovelMergeManager = defaultMerge(),
        getNovelTracks: GetNovelTracks = mockk(relaxed = true),
        insertNovelTrack: InsertNovelTrack = mockk(relaxed = true),
        novelChapterRepository: NovelChapterRepository = mockk(relaxed = true),
        novelDownloadManager: NovelDownloadManager = mockk(relaxed = true),
        novelRepository: NovelRepository = defaultNovelRepo(),
        sourceManager: NovelSourceManager = mockk(relaxed = true),
    ) = MigrateNovelUseCase(
        novelChapterRepository = novelChapterRepository,
        getNovelCategories = mockk(relaxed = true),
        setNovelCategories = mockk(relaxed = true),
        novelMergeManager = novelMergeManager,
        novelDownloadManager = novelDownloadManager,
        updateNovel = updateNovel,
        coverCache = coverCache,
        getNovelTracks = getNovelTracks,
        insertNovelTrack = insertNovelTrack,
        // Every test that isn't about the refresh passes skipTargetRefresh = true and asserts
        // against its pre-synced target chapters; the refresh-contract test injects its own manager.
        sourceManager = sourceManager,
        novelRepository = novelRepository,
        database = mockk(relaxed = true),
    )

    @Test
    fun `cover flag copies the custom cover onto the target and bumps its timestamp`() = runTest {
        val src = File.createTempFile("mig-src", ".0").apply {
            writeText("COVER-BYTES")
            deleteOnExit()
        }
        val dst = File.createTempFile("mig-dst", ".0").apply {
            writeText("")
            deleteOnExit()
        }
        val coverCache = mockk<CoverCache> {
            every { getCustomCoverFile(EntryId.Novel(1L)) } returns src
            every { getCustomCoverFile(EntryId.Novel(2L)) } returns dst
        }
        val updateNovel = mockk<UpdateNovel>(relaxed = true)

        useCase(coverCache, updateNovel)(
            novel(1),
            novel(2),
            setOf(NovelMigrationFlag.COVER),
            replace = false,
            skipTargetRefresh = true,
        )

        dst.readText() shouldBe "COVER-BYTES"
        coVerify { updateNovel.awaitUpdateCoverLastModified(2L) }
    }

    /** The target's NovelUpdate out of the batched favorite swap. */
    private fun capturedTargetUpdate(updates: CapturingSlot<List<NovelUpdate>>): NovelUpdate =
        updates.captured.single { it.favorite == true }

    @Test
    fun `notes flag carries the source notes onto the target`() = runTest {
        val updates = slot<List<NovelUpdate>>()
        val repo = mockk<NovelRepository>(relaxed = true) { coEvery { updateAll(capture(updates)) } returns true }

        useCase(novelRepository = repo)(
            novel(1, notes = "my note"),
            novel(2),
            setOf(NovelMigrationFlag.NOTES),
            replace = false,
            skipTargetRefresh = true,
        )

        capturedTargetUpdate(updates).notes shouldBe "my note"
    }

    @Test
    fun `without the cover or notes flags neither is carried`() = runTest {
        val updates = slot<List<NovelUpdate>>()
        val coverCache = mockk<CoverCache>(relaxed = true)
        val updateNovel = mockk<UpdateNovel>(relaxed = true)
        val repo = mockk<NovelRepository>(relaxed = true) { coEvery { updateAll(capture(updates)) } returns true }

        useCase(coverCache, updateNovel, novelRepository = repo)(
            novel(1, notes = "my note"),
            novel(2),
            emptySet(),
            replace = false,
            skipTargetRefresh = true,
        )

        capturedTargetUpdate(updates).notes shouldBe null
        verify(exactly = 0) { coverCache.getCustomCoverFile(any<EntryId>()) }
        coVerify(exactly = 0) { updateNovel.awaitUpdateCoverLastModified(any()) }
    }

    @Test
    fun `replace swaps the source out of its group and the target in atomically`() = runTest {
        val merge = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 3L)
        }

        useCase(novelMergeManager = merge)(novel(1), novel(2), emptySet(), replace = true, skipTargetRefresh = true)

        coVerify { merge.replaceInGroup(1L, 2L) }
        coVerify(exactly = 0) { merge.merge(any()) }
    }

    @Test
    fun `copy of a merged novel adds the target alongside the source`() = runTest {
        val merge = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 3L)
        }

        useCase(novelMergeManager = merge)(novel(1), novel(2), emptySet(), replace = false, skipTargetRefresh = true)

        coVerify { merge.merge(listOf(1L, 3L, 2L)) }
        coVerify(exactly = 0) { merge.replaceInGroup(any(), any()) }
    }

    @Test
    fun `copy of an unmerged novel is never grouped on migration`() = runTest {
        val merge = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(1L) } returns longArrayOf(1L)
        }

        useCase(novelMergeManager = merge)(novel(1), novel(2), emptySet(), replace = false, skipTargetRefresh = true)

        coVerify(exactly = 0) { merge.merge(any()) }
    }

    @Test
    fun `migration carries tracker links onto the target`() = runTest {
        val getTracks = mockk<GetNovelTracks> { coEvery { await(1L) } returns listOf(novelTrack(novelId = 1L)) }
        val insert = mockk<InsertNovelTrack>(relaxed = true)

        useCase(getNovelTracks = getTracks, insertNovelTrack = insert)(
            novel(1),
            novel(2),
            emptySet(),
            replace = false,
            skipTargetRefresh = true,
        )

        coVerify { insert.await(match { it.novelId == 2L }) }
    }

    @Test
    fun `migration carries the chapter and viewer flags onto the target`() = runTest {
        val updates = slot<List<NovelUpdate>>()
        val repo = mockk<NovelRepository>(relaxed = true) { coEvery { updateAll(capture(updates)) } returns true }
        val source = novel(1).copy(chapterFlags = 0b1010L, viewerFlags = 0b0100L)

        useCase(novelRepository = repo)(source, novel(2), emptySet(), replace = false, skipTargetRefresh = true)

        capturedTargetUpdate(updates).chapterFlags shouldBe 0b1010L
        capturedTargetUpdate(updates).viewerFlags shouldBe 0b0100L
    }

    @Test
    fun `a failed favorite swap fails the migration instead of passing silently`() = runTest {
        val repo = mockk<NovelRepository>(relaxed = true) { coEvery { updateAll(any()) } returns false }

        shouldThrow<IllegalStateException> {
            useCase(novelRepository = repo)(novel(1), novel(2), emptySet(), replace = true, skipTargetRefresh = true)
        }
    }

    @Test
    fun `a failed refresh onto a chapterless target fails the migration`() = runTest {
        // Source unavailable and the relaxed chapter repository reports no target chapters: the
        // manga getOrThrow contract applies and the row must fail rather than migrate onto nothing.
        // (Stubbed outside the mockk block: a bare get(any()) in there binds to MockKMatcherScope.)
        val sources = mockk<NovelSourceManager>()
        every { sources.get(any()) } returns null

        shouldThrow<IllegalStateException> {
            useCase(sourceManager = sources)(novel(1), novel(2), emptySet(), replace = true)
        }
    }

    @Test
    fun `remove-download flag deletes the source's downloaded chapters`() = runTest {
        val repo = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(1L) } returns listOf(chapter(1, 1.0), chapter(2, 2.0))
            coEvery { getByNovelId(2L) } returns emptyList()
        }
        // Downloaded state now comes from the cache via the manager, not a chapter flag.
        val downloadManager = mockk<NovelDownloadManager>(relaxed = true) {
            every { isChapterDownloaded(any(), match { it.id == 1L }) } returns true
        }

        useCase(novelChapterRepository = repo, novelDownloadManager = downloadManager)(
            novel(1),
            novel(2),
            setOf(NovelMigrationFlag.REMOVE_DOWNLOAD),
            replace = false,
            skipTargetRefresh = true,
        )

        verify { downloadManager.deleteChapters(match { chapters -> chapters.map { it.id } == listOf(1L) }) }
    }

    @Test
    fun `migration never auto re-downloads chapters onto the target`() = runTest {
        // The source chapter is downloaded and matches a target chapter, the case the retired
        // re-download carry used to fire on; a silent re-fetch costs metered data, so it never runs.
        val repo = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(1L) } returns listOf(chapter(1, 1.0))
            coEvery { getByNovelId(2L) } returns listOf(chapter(3, 1.0))
        }
        val downloadManager = mockk<NovelDownloadManager>(relaxed = true) {
            every { isChapterDownloaded(any(), match { it.id == 1L }) } returns true
        }

        useCase(novelChapterRepository = repo, novelDownloadManager = downloadManager)(
            novel(1),
            novel(2),
            setOf(NovelMigrationFlag.CHAPTER),
            replace = false,
            skipTargetRefresh = true,
        )

        verify(exactly = 0) { downloadManager.downloadChapters(any()) }
    }

    private fun chapter(
        id: Long,
        number: Double,
        read: Boolean = false,
        bookmark: Boolean = false,
        progress: Long = 0,
        dateFetch: Long = 0,
    ) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = "Chapter $number",
        read = read,
        bookmark = bookmark,
        lastTextProgress = progress,
        chapterNumber = number,
        sourceOrder = id,
        dateFetch = dateFetch,
        dateUpload = 0,
        page = "",
    )

    @Test
    fun `an exact number match copies read bookmark and progress onto the target chapter`() {
        val current = listOf(chapter(1, 1.0, read = true, bookmark = true, progress = 4200))
        val target = listOf(chapter(10, 1.0))

        val result = computeChapterMigration(current, target)

        result.single().let {
            it.id shouldBe 10L
            it.read shouldBe true
            it.bookmark shouldBe true
            it.lastTextProgress shouldBe 4200
        }
    }

    @Test
    fun `an exact number match carries the source chapter's fetch date`() {
        val current = listOf(chapter(1, 1.0, dateFetch = 1234))
        val target = listOf(chapter(10, 1.0, dateFetch = 9999))

        computeChapterMigration(current, target).single().dateFetch shouldBe 1234
    }

    @Test
    fun `unmatched target chapters at or below the highest read number are marked read`() {
        // Source read up to chapter 3; target numbers chapters differently but 1 and 2 fall under 3.
        val current = listOf(
            chapter(1, 1.0, read = true),
            chapter(2, 2.0, read = true),
            chapter(3, 3.0, read = true),
        )
        val target = listOf(chapter(10, 1.5), chapter(11, 2.5), chapter(12, 9.0))

        val result = computeChapterMigration(current, target)

        result.filter { it.read }.map { it.id } shouldContainExactlyInAnyOrder listOf(10L, 11L)
    }

    @Test
    fun `unrecognized target chapter numbers are skipped`() {
        val current = listOf(chapter(1, 1.0, read = true))
        val target = listOf(chapter(10, -1.0))

        computeChapterMigration(current, target).shouldContainExactlyInAnyOrder(emptyList())
    }

    @Test
    fun `a target chapter whose state already matches is not returned`() {
        val current = listOf(chapter(1, 1.0, read = false))
        val target = listOf(chapter(10, 1.0, read = false))

        computeChapterMigration(current, target).shouldContainExactlyInAnyOrder(emptyList())
    }

    @Test
    fun `with nothing read in the source no extra chapters are swept to read`() {
        val current = listOf(chapter(1, 1.0, bookmark = true))
        val target = listOf(chapter(10, 1.0), chapter(11, 2.0))

        val result = computeChapterMigration(current, target)

        // Only the matched chapter changes (gains the bookmark); chapter 2 stays untouched.
        result.single().id shouldBe 10L
    }
}
