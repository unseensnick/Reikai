package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel

class NovelBackupRoundTripTest {

    private fun novel(id: Long, url: String, source: String, title: String = "T") =
        Novel.create().copy(id = id, url = url, source = source, title = title, favorite = true)

    private fun restorer(repo: NovelRepository, repository: MergeGroupRepository) = NovelRestorer(
        novelRepository = repo,
        novelChapterRepository = mockk(relaxed = true),
        categoryRepository = mockk(relaxed = true),
        novelTrackRepository = mockk(relaxed = true),
        mergeGroupRepository = repository,
        setCustomNovelInfo = mockk(relaxed = true),
        database = mockk(relaxed = true),
    )

    @Test
    fun `restoring an older backup keeps the newer local novel`() = runTest {
        // Device copy was edited since the backup (version 5 > 2), so its details must survive.
        val dbNovel = novel(10, "u", "s1").copy(description = "local", version = 5)
        val backup = BackupNovel(source = "s1", url = "u", title = "T", description = "old", version = 2)

        val written = slot<Novel>()
        val syncing = slot<Boolean>()
        val repo = mockk<NovelRepository>(relaxed = true) {
            coEvery { getByUrlAndSource("u", "s1") } returns dbNovel
            coEvery { update(capture(written), capture(syncing)) } returns true
        }

        restorer(repo, mockk(relaxed = true)).restore(backup, emptyList())

        written.captured.description shouldBe "local"
        written.captured.version shouldBe 5L
        written.captured.id shouldBe 10L
        // Restore writes are marked syncing so the version trigger doesn't bump on the write itself.
        syncing.captured shouldBe true
    }

    @Test
    fun `restoring a newer backup overwrites the older local novel`() = runTest {
        // Backup is newer (version 5 > 2), so the backup's details win.
        val dbNovel = novel(10, "u", "s1").copy(description = "local", version = 2)
        val backup = BackupNovel(source = "s1", url = "u", title = "T", description = "new", version = 5)

        val written = slot<Novel>()
        val repo = mockk<NovelRepository>(relaxed = true) {
            coEvery { getByUrlAndSource("u", "s1") } returns dbNovel
            coEvery { update(capture(written), any()) } returns true
        }

        restorer(repo, mockk(relaxed = true)).restore(backup, emptyList())

        written.captured.description shouldBe "new"
        written.captured.version shouldBe 5L
        written.captured.id shouldBe 10L
    }

    @Test
    fun `merge groups survive an id remap across backup then restore`() = runTest {
        // Backup side: a persisted group of two favorited novels on different sources.
        val favorites = listOf(novel(1, "a", "s1"), novel(5, "b", "s2"))
        val backupRepo = mockk<NovelRepository> {
            coEvery { getFavorites() } returns favorites
            coEvery { getById(1L) } returns favorites[0]
            coEvery { getById(5L) } returns favorites[1]
        }
        val backupMergeRepo = mockk<MergeGroupRepository> {
            coEvery { getAllMemberships(ContentType.NOVELS) } returns mapOf(1L to 100L, 5L to 100L)
        }
        val creator = NovelBackupCreator(
            novelRepository = backupRepo,
            novelChapterRepository = mockk(relaxed = true),
            categoryRepository = mockk(relaxed = true),
            novelTrackRepository = mockk(relaxed = true),
            mergeGroupRepository = backupMergeRepo,
            customNovelInfoRepository = mockk(relaxed = true),
            database = mockk(relaxed = true),
        )
        // Library + merges only; skip chapters/categories/tracking/history so no DB is touched.
        val options = BackupOptions(
            libraryEntries = true,
            chapters = false,
            categories = false,
            tracking = false,
            history = false,
        )

        val merges = creator.novelMerges(options)

        // The group is serialized as stable {url, source} refs, not the raw ids.
        merges.map { group -> group.refs.map { it.url to it.source } } shouldContainExactly
            listOf(listOf("a" to "s1", "b" to "s2"))

        // Restore side: the same two novels come back with fresh ids 10 and 20.
        val restoreMergeRepo = mockk<MergeGroupRepository>(relaxed = true)
        val restoreRepo = mockk<NovelRepository> {
            coEvery { getByUrlAndSource("a", "s1") } returns novel(10, "a", "s1")
            coEvery { getByUrlAndSource("b", "s2") } returns novel(20, "b", "s2")
        }

        restorer(restoreRepo, restoreMergeRepo).restoreMerges(merges)

        // The group is materialized against the restored ids via the repository.
        coVerify { restoreMergeRepo.merge(ContentType.NOVELS, listOf(10L, 20L)) }
    }

    @Test
    fun `a merge group is dropped when fewer than two members resolve on restore`() = runTest {
        val restoreMergeRepo = mockk<MergeGroupRepository>(relaxed = true)
        val restoreRepo = mockk<NovelRepository> {
            coEvery { getByUrlAndSource("a", "s1") } returns novel(10, "a", "s1")
            // The second member's novel wasn't restored (e.g. its source wasn't backed up).
            coEvery { getByUrlAndSource("b", "s2") } returns null
        }
        val group = BackupNovelMergeGroup(
            refs = listOf(BackupNovelSourceRef("a", "s1"), BackupNovelSourceRef("b", "s2")),
        )

        restorer(restoreRepo, restoreMergeRepo).restoreMerges(listOf(group))

        // Only one member resolved, so no group is created.
        coVerify(exactly = 0) { restoreMergeRepo.merge(any(), any()) }
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
}
