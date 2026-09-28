package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryContentType
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category

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

    /** Runs [backupManga] through restore() against [deviceCategories] and returns the ids it files it under. */
    private suspend fun restoredCategoryIds(
        backupManga: BackupManga,
        deviceCategories: List<Category> = this.deviceCategories,
    ): List<Long> {
        val entries = mutableListOf<RestoredManga>()
        val restorer = MangaRestorer(
            restoreRepository = mockk {
                coEvery { restoreManga(any(), any()) } answers { entries += firstArg<List<RestoredManga>>() }
            },
            getCategories = mockk { coEvery { await() } returns deviceCategories },
            fetchInterval = mockk(relaxed = true),
            getMangaByUrlAndSourceId = mockk(),
            restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
            setCustomMangaInfo = mockk(relaxed = true),
        )

        restorer.restore(listOf(backupManga), backupCategories)
        return entries.single().categoryIds
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

    @Test
    fun `a manga binds to the manga category when a universal one shares its name`() = runTest {
        val backupManga = BackupManga(source = 1L, url = "u", title = "T", categories = listOf(5))
        val sameName = listOf(
            Category(id = 100, name = "Reading", order = 0, flags = 0, contentType = CategoryContentType.MANGA),
            Category(id = 300, name = "Reading", order = 1, flags = 0, contentType = CategoryContentType.UNIVERSAL),
        )

        restoredCategoryIds(backupManga, sameName) shouldContainExactly listOf(100)
    }
}
