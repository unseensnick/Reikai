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
 * The update happens inside a suspend transaction, run inline here; the chapters update's read (arg 4)
 * and last_page_read (arg 6) are captured.
 */
class MangaRestoreChaptersTest {

    private val mangaId = 7L

    /** Restores [backup] over [dbChapters] and returns (read, lastPageRead) of the single chapter update. */
    private suspend fun restoredChapterUpdate(
        backup: BackupChapter,
        dbChapter: Chapter,
    ): Pair<Boolean?, Long?> {
        val updates = mutableListOf<Pair<Boolean?, Long?>>()
        val database = mockk<Database>(relaxed = true) {
            coEvery { transaction(any(), any()) } coAnswers {
                secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>().invoke(mockk(relaxed = true))
            }
            coEvery {
                chaptersQueries.update(
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                updates.add(arg<Boolean?>(4) to arg<Long?>(6))
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
            reikaiLibraryPreferences = mockk(relaxed = true),
            mangaMetadataRepository = mockk(relaxed = true),
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

        restoredChapterUpdate(backup, dbChapter).first shouldBe true
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

        restoredChapterUpdate(backup, dbChapter).second shouldBe 30L
    }
}
