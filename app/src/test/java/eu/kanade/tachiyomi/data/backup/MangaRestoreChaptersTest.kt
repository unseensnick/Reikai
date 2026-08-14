package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.data.Database
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga

/**
 * When a backup is restored over a manga that already has chapters, per-chapter read state must merge
 * rather than overwrite: a chapter read on either side stays read, and locally-recorded reading
 * progress is preserved when the backup hasn't progressed. Losing this silently rewinds a user's
 * progress, so it's a high data-loss-risk path.
 *
 * The update happens inside a suspend transaction, run inline here; the chapters update's read (arg 4),
 * last_page_read (arg 6) and page_count (arg 14) are captured.
 */
class MangaRestoreChaptersTest {

    private val mangaId = 7L

    /** One restored chapter update: read, lastPageRead and pageCount as written. */
    private data class RestoredUpdate(val read: Boolean?, val lastPageRead: Long?, val pageCount: Long?)

    /** Restores [backup] over [dbChapter] and returns the single chapter update it wrote. */
    private suspend fun restoredChapterUpdate(
        backup: BackupChapter,
        dbChapter: Chapter,
    ): RestoredUpdate {
        val updates = mutableListOf<RestoredUpdate>()
        val database = mockk<Database>(relaxed = true) {
            coEvery { transaction(any(), any()) } coAnswers {
                secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>().invoke(mockk(relaxed = true))
            }
            coEvery {
                chaptersQueries.update(
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                updates.add(RestoredUpdate(arg<Boolean?>(4), arg<Long?>(6), arg<Long?>(14)))
                0L
            }
        }
        val dbManga = Manga.create().copy(id = mangaId, url = "u", source = 1L)
        val restorer = MangaRestorer(
            database = database,
            getCategories = mockk { coEvery { await() } returns emptyList() },
            getMangaByUrlAndSourceId = mockk<GetMangaByUrlAndSourceId> {
                coEvery { await("u", 1L) } returns dbManga
            },
            getChaptersByMangaId = mockk<GetChaptersByMangaId> {
                coEvery { await(mangaId) } returns listOf(dbChapter)
            },
            updateManga = mockk(relaxed = true),
            getTracks = mockk(relaxed = true),
            insertTrack = mockk(relaxed = true),
            fetchInterval = mockk(relaxed = true),
            restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
            mangaMetadataRepository = mockk(relaxed = true),
            setCustomMangaInfo = mockk(relaxed = true),
        )

        restorer.restore(BackupManga(source = 1L, url = "u", title = "T", chapters = listOf(backup)), emptyList())
        return updates.single()
    }

    @Test
    fun `a chapter read locally stays read even when the backup has it unread`() = runTest {
        val backup = BackupChapter(url = "c1", name = "C1", read = false, lastPageRead = 0)
        val dbChapter = Chapter.create().copy(
            id = 1,
            mangaId = mangaId,
            url = "c1",
            name = "C1",
            read = true,
            lastPageRead = 50,
        )

        restoredChapterUpdate(backup, dbChapter).read shouldBe true
    }

    @Test
    fun `local reading progress is kept when the backup chapter has not progressed`() = runTest {
        val backup = BackupChapter(url = "c1", name = "C1", read = false, lastPageRead = 0)
        val dbChapter = Chapter.create().copy(
            id = 1,
            mangaId = mangaId,
            url = "c1",
            name = "C1",
            read = false,
            lastPageRead = 30,
        )

        restoredChapterUpdate(backup, dbChapter).lastPageRead shouldBe 30L
    }

    @Test
    fun `a page count already on the device survives a backup that predates the column`() = runTest {
        val backup = BackupChapter(url = "c1", name = "C1", read = false, lastPageRead = 0)
        val dbChapter = Chapter.create().copy(
            id = 1,
            mangaId = mangaId,
            url = "c1",
            name = "C1",
            read = false,
            lastPageRead = 30,
            pageCount = 38,
        )

        restoredChapterUpdate(backup, dbChapter).pageCount shouldBe 38L
    }

    @Test
    fun `a page count in the backup fills one the device does not have`() = runTest {
        val backup = BackupChapter(url = "c1", name = "C1", read = true, lastPageRead = 37, pageCount = 38)
        val dbChapter = Chapter.create().copy(
            id = 1,
            mangaId = mangaId,
            url = "c1",
            name = "C1",
            read = false,
            lastPageRead = 0,
        )

        restoredChapterUpdate(backup, dbChapter).pageCount shouldBe 38L
    }
}
