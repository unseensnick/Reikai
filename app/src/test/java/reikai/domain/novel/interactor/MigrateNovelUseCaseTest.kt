package reikai.domain.novel.interactor

import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.download.NovelDownloadManager
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

    private fun useCase(
        coverCache: CoverCache = mockk(relaxed = true),
        updateNovel: UpdateNovel = mockk(relaxed = true),
        novelMergeManager: NovelMergeManager = defaultMerge(),
        getNovelTracks: GetNovelTracks = mockk(relaxed = true),
        insertNovelTrack: InsertNovelTrack = mockk(relaxed = true),
        novelChapterRepository: NovelChapterRepository = mockk(relaxed = true),
        novelDownloadManager: NovelDownloadManager = mockk(relaxed = true),
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
        // Relaxed source manager returns null for get(), so the target-sync step is skipped and these
        // tests keep asserting against the pre-synced target chapters they set up.
        sourceManager = mockk(relaxed = true),
        novelRepository = mockk(relaxed = true),
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
            every { getCustomCoverFile(-1L) } returns src
            every { getCustomCoverFile(-2L) } returns dst
        }
        val updateNovel = mockk<UpdateNovel>(relaxed = true)

        useCase(coverCache, updateNovel)(novel(1), novel(2), setOf(NovelMigrationFlag.COVER), replace = false)

        dst.readText() shouldBe "COVER-BYTES"
        coVerify { updateNovel.awaitUpdateCoverLastModified(2L) }
    }

    @Test
    fun `notes flag carries the source notes onto the target`() = runTest {
        val update = slot<NovelUpdate>()
        val updateNovel = mockk<UpdateNovel>(relaxed = true) { coEvery { await(capture(update)) } returns true }

        useCase(
            mockk(relaxed = true),
            updateNovel,
        )(novel(1, notes = "my note"), novel(2), setOf(NovelMigrationFlag.NOTES), replace = false)

        update.captured.notes shouldBe "my note"
    }

    @Test
    fun `without the cover or notes flags neither is carried`() = runTest {
        val update = slot<NovelUpdate>()
        val coverCache = mockk<CoverCache>(relaxed = true)
        val updateNovel = mockk<UpdateNovel>(relaxed = true) { coEvery { await(capture(update)) } returns true }

        useCase(coverCache, updateNovel)(novel(1, notes = "my note"), novel(2), emptySet(), replace = false)

        update.captured.notes shouldBe null
        verify(exactly = 0) { coverCache.getCustomCoverFile(any()) }
        coVerify(exactly = 0) { updateNovel.awaitUpdateCoverLastModified(any()) }
    }

    @Test
    fun `replace of a merged novel swaps the source out and the target into the group`() = runTest {
        val merge = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 3L)
            coEvery { removeFromGroup(match { it.contentEquals(longArrayOf(1L, 3L)) }, listOf(1L)) } returns
                longArrayOf(3L)
        }

        useCase(novelMergeManager = merge)(novel(1), novel(2), emptySet(), replace = true)

        coVerify { merge.removeFromGroup(match { it.contentEquals(longArrayOf(1L, 3L)) }, listOf(1L)) }
        coVerify { merge.merge(listOf(3L, 2L)) }
    }

    @Test
    fun `copy of a merged novel adds the target alongside the source`() = runTest {
        val merge = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(1L) } returns longArrayOf(1L, 3L)
        }

        useCase(novelMergeManager = merge)(novel(1), novel(2), emptySet(), replace = false)

        coVerify { merge.merge(listOf(1L, 3L, 2L)) }
        coVerify(exactly = 0) { merge.removeFromGroup(any(), any()) }
    }

    @Test
    fun `an unmerged novel is never grouped on migration`() = runTest {
        val merge = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { computeRelatedIds(1L) } returns longArrayOf(1L)
        }

        useCase(novelMergeManager = merge)(novel(1), novel(2), emptySet(), replace = true)

        coVerify(exactly = 0) { merge.merge(any()) }
    }

    @Test
    fun `migration carries tracker links onto the target`() = runTest {
        val getTracks = mockk<GetNovelTracks> { coEvery { await(1L) } returns listOf(novelTrack(novelId = 1L)) }
        val insert = mockk<InsertNovelTrack>(relaxed = true)

        useCase(getNovelTracks = getTracks, insertNovelTrack = insert)(novel(1), novel(2), emptySet(), replace = false)

        coVerify { insert.await(match { it.novelId == 2L }) }
    }

    @Test
    fun `migration carries the chapter and viewer flags onto the target`() = runTest {
        val update = slot<NovelUpdate>()
        val updateNovel = mockk<UpdateNovel>(relaxed = true) { coEvery { await(capture(update)) } returns true }
        val source = novel(1).copy(chapterFlags = 0b1010L, viewerFlags = 0b0100L)

        useCase(updateNovel = updateNovel)(source, novel(2), emptySet(), replace = false)

        update.captured.chapterFlags shouldBe 0b1010L
        update.captured.viewerFlags shouldBe 0b0100L
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
        )

        verify { downloadManager.deleteChapters(match { chapters -> chapters.map { it.id } == listOf(1L) }) }
    }

    @Test
    fun `with remove-download the chapter re-download is skipped so the delete isn't undone`() = runTest {
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
            setOf(NovelMigrationFlag.CHAPTER, NovelMigrationFlag.REMOVE_DOWNLOAD),
            replace = false,
        )

        verify(exactly = 0) { downloadManager.downloadChapters(any()) }
        verify { downloadManager.deleteChapters(any()) }
    }

    private fun chapter(
        id: Long,
        number: Double,
        read: Boolean = false,
        bookmark: Boolean = false,
        progress: Long = 0,
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
        dateFetch = 0,
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

    @Test
    fun `re-download covers the target chapters matching downloaded source chapters`() {
        val current = listOf(
            chapter(1, 1.0),
            chapter(2, 2.0),
            chapter(3, 3.0),
        )
        val target = listOf(chapter(10, 1.0), chapter(11, 2.0), chapter(12, 3.0))

        // Source chapters 1 and 3 are downloaded on disk (ids in the cache set).
        chaptersToRedownload(current, target, setOf(1L, 3L)).map { it.id } shouldContainExactlyInAnyOrder
            listOf(10L, 12L)
    }

    @Test
    fun `re-download is empty when the source had no downloads`() {
        val current = listOf(chapter(1, 1.0, read = true))
        val target = listOf(chapter(10, 1.0))

        chaptersToRedownload(current, target, emptySet()).shouldContainExactlyInAnyOrder(emptyList())
    }
}
