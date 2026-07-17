package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga

/**
 * Backups store a manga's categories as the *order* indices of the source device's categories, never
 * names or ids (ids differ per device). On restore each order is mapped back through the backup's own
 * category list to a name, then to whatever id that name has on this device. A category whose name
 * doesn't exist locally is dropped rather than mis-assigned. This guards that remap, the highest
 * data-loss-risk corner of restore.
 */
class MangaRestoreCategoriesTest {

    private val deviceCategories = listOf(
        Category(id = 100, name = "Reading", order = 0, flags = 0),
        Category(id = 200, name = "Completed", order = 1, flags = 0),
    )

    // Backup-side categories: same names, different orders/ids, plus one ("Ghost") absent locally.
    private val backupCategories = listOf(
        BackupCategory(name = "Reading", order = 5),
        BackupCategory(name = "Completed", order = 9),
        BackupCategory(name = "Ghost", order = 3),
    )

    /**
     * Runs [backupManga] through restore() against [deviceCategories] and returns the category ids
     * the manga gets assigned (the second arg of each mangas_categories insert).
     */
    private suspend fun restoredCategoryIds(backupManga: BackupManga): List<Long> {
        val insertedCategoryIds = mutableListOf<Long>()
        val database = mockk<Database>(relaxed = true) {
            // Run the suspend transaction body inline so the inserts actually fire.
            coEvery { transaction(any(), any()) } coAnswers {
                // arg 0 = noEnclosing, arg 1 = the suspend body (arg 2 is the continuation).
                secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>().invoke(mockk(relaxed = true))
            }
            coEvery { mangas_categoriesQueries.insert(any(), any()) } coAnswers {
                val categoryId = secondArg<Long>()
                insertedCategoryIds.add(categoryId)
                categoryId
            }
        }
        // Existing-manga path (dbManga != null) avoids the insertReturningId(...).awaitAsOne() of the
        // new-manga branch, which a relaxed mock can't satisfy.
        val dbManga = Manga.create().copy(id = 7, url = "u", source = 1L)
        val restorer = MangaRestorer(
            database = database,
            getCategories = mockk { coEvery { await() } returns deviceCategories },
            getMangaByUrlAndSourceId = mockk<GetMangaByUrlAndSourceId> {
                coEvery { await("u", 1L) } returns dbManga
            },
            getChaptersByMangaId = mockk<GetChaptersByMangaId> { coEvery { await(7) } returns emptyList() },
            updateManga = mockk(relaxed = true),
            getTracks = mockk(relaxed = true),
            insertTrack = mockk(relaxed = true),
            fetchInterval = mockk(relaxed = true),
            mergeGroupRepository = mockk(relaxed = true),
            mangaMetadataRepository = mockk(relaxed = true),
            setCustomMangaInfo = mockk(relaxed = true),
        )

        restorer.restore(backupManga, backupCategories)
        return insertedCategoryIds
    }

    @Test
    fun `category orders map through backup names to this device's category ids`() = runTest {
        val backupManga = BackupManga(source = 1L, url = "u", title = "T", categories = listOf(5, 9))

        restoredCategoryIds(backupManga) shouldContainExactly listOf(100, 200)
    }

    @Test
    fun `a category whose name is absent locally is dropped`() = runTest {
        // Order 3 is "Ghost", which has no match in deviceCategories.
        val backupManga = BackupManga(source = 1L, url = "u", title = "T", categories = listOf(5, 3, 9))

        restoredCategoryIds(backupManga) shouldContainExactly listOf(100, 200)
    }
}
