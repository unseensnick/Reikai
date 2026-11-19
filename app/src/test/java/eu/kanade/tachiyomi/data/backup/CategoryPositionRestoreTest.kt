package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.backup.RestoreCategoryOrder
import reikai.domain.category.CategoryContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.NewCategory

/**
 * Manga and novel categories share one table and one ordering, so a category a restore creates has to
 * take a position no other row holds, whichever library it belongs to (mihon 8bfa495f3), and the
 * categories it creates come back in the backup's order across both libraries.
 */
class CategoryPositionRestoreTest {

    @Test
    fun `a novel category restored beside manga categories takes a position of its own`() = runTest {
        MangaRestoreHarness.create().use { harness ->
            listOf("A", "B", "C").forEach { name -> harness.categories.insert(NewCategory(name = name, flags = 0)) }
            harness.novelCategoryRestorer().restoreCategories(listOf(BackupNovelCategory(name = "Novels", order = 0)))

            harness.categories.getUnfiltered()
                .filterNot(Category::isSystemCategory)
                .map { it.order }
                .shouldBeUnique()
        }
    }

    @Test
    fun `a novel category backed up above a universal one restores above it`() = runTest {
        restoredNames(
            device = emptyList(),
            manga = listOf(BackupCategory(name = "A", order = 1, contentType = CategoryContentType.UNIVERSAL)),
            novel = listOf(BackupNovelCategory(name = "N", order = 0)),
        ) shouldBe listOf("N", "A")
    }

    @Test
    fun `the device's own categories keep their places ahead of the restored ones`() = runTest {
        restoredNames(
            device = listOf("X"),
            manga = listOf(
                BackupCategory(name = "A", order = 1, contentType = CategoryContentType.UNIVERSAL),
                BackupCategory(name = "B", order = 2),
            ),
            novel = listOf(BackupNovelCategory(name = "N", order = 0)),
        ) shouldBe listOf("X", "N", "A", "B")
    }

    @Test
    fun `a category the device already has keeps its place`() = runTest {
        restoredNames(
            device = listOf("A"),
            manga = listOf(BackupCategory(name = "A", order = 5), BackupCategory(name = "B", order = 3)),
            novel = listOf(BackupNovelCategory(name = "N", order = 0)),
        ) shouldBe listOf("A", "N", "B")
    }

    /** Restores the backup's categories over manga-only [device] rows and returns every row's name in order. */
    private suspend fun restoredNames(
        device: List<String>,
        manga: List<BackupCategory>,
        novel: List<BackupNovelCategory>,
    ): List<String> = MangaRestoreHarness.create().use { harness ->
        device.forEach { harness.categories.insert(NewCategory(name = it, flags = 0)) }
        restoreEncoded(
            Backup(backupManga = emptyList(), backupCategories = manga, backupNovelCategories = novel),
            RestoreOptions(libraryEntries = false, appSettings = false),
            categoriesRestorer = CategoriesRestorer(
                harness.categories,
                GetCategories(harness.categories),
                mockk(relaxed = true),
                FakeLibraryManualOrderRepository(),
            ),
            novelRestorer = harness.novelCategoryRestorer(),
            restoreCategoryOrder = RestoreCategoryOrder(harness.categories),
        )
        harness.categories.getUnfiltered().filterNot(Category::isSystemCategory).map { it.name }
    }
}
