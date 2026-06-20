package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelChapter
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import tachiyomi.core.common.preference.Preference

class NovelBackupRoundTripTest {

    private fun novel(id: Long, url: String, source: String, title: String = "T") =
        Novel.create().copy(id = id, url = url, source = source, title = title, favorite = true)

    private fun <T> pref(value: T): Preference<T> = mockk(relaxed = true) { every { get() } returns value }

    @Test
    fun `merge groups survive an id remap across backup then restore`() = runTest {
        // Backup side: a manual merge "1,5" over two favorited novels on different sources.
        val favorites = listOf(novel(1, "a", "s1"), novel(5, "b", "s2"))
        val backupPrefs = mockk<ReikaiLibraryPreferences> {
            every { novelManualMerges } returns pref(setOf("1,5"))
            every { novelManualUnmerges } returns pref(emptySet())
        }
        val backupRepo = mockk<NovelRepository> {
            coEvery { getFavorites() } returns favorites
        }
        val creator = NovelBackupCreator(
            novelRepository = backupRepo,
            novelChapterRepository = mockk(relaxed = true),
            novelCategoryRepository = mockk(relaxed = true),
            novelTrackRepository = mockk(relaxed = true),
            preferences = backupPrefs,
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

        val data = creator(options)

        // The group is serialized as stable {url, source} refs, not the raw ids.
        data.merges.map { group -> group.refs.map { it.url to it.source } } shouldContainExactly
            listOf(listOf("a" to "s1", "b" to "s2"))

        // Restore side: the same two novels come back with fresh ids 10 and 20.
        val mergeSlot = slot<Set<String>>()
        val restorePrefs = mockk<ReikaiLibraryPreferences> {
            every { novelManualMerges } returns mockk(relaxed = true) {
                every { get() } returns emptySet()
                every { set(capture(mergeSlot)) } returns Unit
            }
            every { novelManualUnmerges } returns pref(emptySet())
        }
        val restoreRepo = mockk<NovelRepository> {
            coEvery { getByUrlAndSource("a", "s1") } returns novel(10, "a", "s1")
            coEvery { getByUrlAndSource("b", "s2") } returns novel(20, "b", "s2")
        }
        val restorer = NovelRestorer(
            novelRepository = restoreRepo,
            novelChapterRepository = mockk(relaxed = true),
            novelCategoryRepository = mockk(relaxed = true),
            novelTrackRepository = mockk(relaxed = true),
            preferences = restorePrefs,
            database = mockk(relaxed = true),
        )

        restorer.restoreMerges(data.merges, emptyList())

        // The merge is rebuilt against the restored ids, sorted and comma-joined as the pref expects.
        mergeSlot.captured shouldBe setOf("10,20")
    }

    @Test
    fun `a merge group is dropped when fewer than two members resolve on restore`() = runTest {
        val restorePrefs = mockk<ReikaiLibraryPreferences> {
            every { novelManualMerges } returns mockk(relaxed = true) { every { get() } returns emptySet() }
            every { novelManualUnmerges } returns pref(emptySet())
        }
        val restoreRepo = mockk<NovelRepository> {
            coEvery { getByUrlAndSource("a", "s1") } returns novel(10, "a", "s1")
            // The second member's novel wasn't restored (e.g. its source wasn't backed up).
            coEvery { getByUrlAndSource("b", "s2") } returns null
        }
        val restorer = NovelRestorer(
            novelRepository = restoreRepo,
            novelChapterRepository = mockk(relaxed = true),
            novelCategoryRepository = mockk(relaxed = true),
            novelTrackRepository = mockk(relaxed = true),
            preferences = restorePrefs,
            database = mockk(relaxed = true),
        )

        val group = eu.kanade.tachiyomi.data.backup.models.BackupNovelMergeGroup(
            refs = listOf(
                eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef("a", "s1"),
                eu.kanade.tachiyomi.data.backup.models.BackupNovelSourceRef("b", "s2"),
            ),
        )

        restorer.restoreMerges(listOf(group), emptyList())

        // Only one member resolved, so no merge is written (no set() with a non-empty value).
        io.mockk.verify(exactly = 0) { restorePrefs.novelManualMerges.set(any()) }
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
            editedFlags = 8,
        )

        val novel = backup.toNovelImpl()

        novel.source shouldBe "s1"
        novel.url shouldBe "u"
        novel.title shouldBe "Title"
        novel.status shouldBe 2L
        novel.thumbnailUrl shouldBe "cover"
        novel.favorite shouldBe true
        novel.totalPages shouldBe 3L
        novel.editedFlags shouldBe 8L
    }

    @Test
    fun `BackupNovelChapter restores under the new novel id and is never marked downloaded`() {
        val chapter = BackupNovelChapter(url = "c1", name = "Chapter 1", read = true, lastTextProgress = 4200)
            .toChapterImpl(novelId = 42)

        chapter.novelId shouldBe 42L
        chapter.url shouldBe "c1"
        chapter.read shouldBe true
        chapter.lastTextProgress shouldBe 4200L
        chapter.isDownloaded shouldBe false
    }
}
