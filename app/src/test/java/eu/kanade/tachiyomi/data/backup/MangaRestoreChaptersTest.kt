package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * A manga chapter's page count survives a restore from either side. The read-state merge both types
 * share is pinned by RestoreMergeConformanceTest. Runs the real restore over real SQL.
 */
class MangaRestoreChaptersTest {

    /** Restores [backup] over the device's [device] chapter and returns the page count stored after. */
    private suspend fun restoredPageCount(backup: BackupChapter, device: Chapter): Long =
        MangaRestoreHarness.create().use { harness ->
            val mangaId = harness.insert(Manga.create().copy(url = "u", source = 1L, title = "T"))
            harness.insert(device.copy(mangaId = mangaId))

            harness.restorer().restore(
                listOf(BackupManga(source = 1L, url = "u", title = "T", chapters = listOf(backup))),
                emptyList(),
            )
            harness.chapters.getChapterByMangaId(mangaId).single().pageCount
        }

    @Test
    fun `a page count already on the device survives a backup that predates the column`() = runTest {
        val backup = BackupChapter(url = "c1", name = "C1", read = false, lastPageRead = 0)
        val device = Chapter.create().copy(url = "c1", name = "C1", read = false, lastPageRead = 30, pageCount = 38)

        restoredPageCount(backup, device) shouldBe 38L
    }

    @Test
    fun `a page count in the backup fills one the device does not have`() = runTest {
        val backup = BackupChapter(url = "c1", name = "C1", read = true, lastPageRead = 37, pageCount = 38)
        val device = Chapter.create().copy(url = "c1", name = "C1", read = false, lastPageRead = 0)

        restoredPageCount(backup, device) shouldBe 38L
    }
}
