package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSortOverridesStored
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.domain.library.isSortOverridden
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * What a restore makes of a backup's category sorts. Per-category sort is on exactly when a
 * category of either library keeps its own sort once both are restored. A backup written before the
 * override bit (Mihon's, or Reikai's before 0.3.0) holds each category's own sort in its flags alone, so
 * the restore marks the ones apart from the global, and only where the backup's sorts differ at all.
 */
class CategorizedDisplayRestoreTest {

    // This device's global sort is the default, Alphabetical ascending.
    private val libraryPreferences = LibraryPreferences(EmittingPreferenceStore())
    private val lastReadAsc = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Ascending)
    private val totalDesc = LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending)
    private val alphabeticalAsc = LibrarySort.default

    /** Restores [backup]'s categories into a fresh database and returns its user categories. */
    private suspend fun restore(backup: Backup, appSettings: Boolean = false): List<Category> =
        MangaRestoreHarness.create().use { harness ->
            restoreEncoded(
                backup,
                RestoreOptions(
                    libraryEntries = false,
                    categories = true,
                    appSettings = appSettings,
                    extensionStores = false,
                    sourceSettings = false,
                    savedSearches = false,
                ),
                categoriesRestorer = CategoriesRestorer(
                    harness.categories,
                    GetCategories(harness.categories),
                    libraryPreferences,
                ),
                novelRestorer = harness.novelCategoryRestorer(),
            )
            harness.categories.getUnfiltered().filterNot(Category::isSystemCategory)
        }

    private suspend fun restoredSwitch(backup: Backup): Boolean {
        restore(backup)
        return libraryPreferences.categorizedDisplaySettings.get()
    }

    private suspend fun overrides(backup: Backup, appSettings: Boolean = false): Map<String, Boolean> =
        restore(backup, appSettings).associate { it.name to isSortOverridden(it) }

    private fun backup(vararg categories: BackupCategory, marked: Boolean = false) = Backup(
        backupManga = emptyList(),
        backupCategories = categories.toList(),
        backupSortOverridesStored = BackupSortOverridesStored().takeIf { marked },
    )

    @Test
    fun `a novel category keeping its own sort turns per-category sort on`() = runTest {
        restoredSwitch(
            Backup(
                backupManga = emptyList(),
                backupNovelCategories = listOf(
                    BackupNovelCategory("Fantasy", 1, 5, lastReadAsc.flag or CATEGORY_SORT_CUSTOMIZED),
                ),
                backupSortOverridesStored = BackupSortOverridesStored(),
            ),
        ) shouldBe true
    }

    @Test
    fun `a hidden category alone leaves per-category sort off`() = runTest {
        restoredSwitch(backup(BackupCategory("Hidden", 1, 3, CATEGORY_HIDDEN_MASK), marked = true)) shouldBe false
    }

    @Test
    fun `a sort left behind by a reset leaves per-category sort off`() = runTest {
        restoredSwitch(backup(BackupCategory("Reset", 1, 3, lastReadAsc.flag), marked = true)) shouldBe false
    }

    @Test
    fun `a manga category keeping its own sort turns per-category sort on`() = runTest {
        restoredSwitch(
            backup(BackupCategory("Over", 1, 3, lastReadAsc.flag or CATEGORY_SORT_CUSTOMIZED), marked = true),
        ) shouldBe true
    }

    @Test
    fun `a Mihon backup keeps each category's sort that differs from the global`() = runTest {
        overrides(
            backup(
                BackupCategory("A", 1, 1, lastReadAsc.flag),
                BackupCategory("B", 2, 2, totalDesc.flag),
                BackupCategory("C", 3, 3, alphabeticalAsc.flag),
            ),
        ) shouldBe mapOf("A" to true, "B" to true, "C" to false)
    }

    @Test
    fun `a Mihon backup restored with its settings is read against its own global sort`() = runTest {
        val sorts = backup(BackupCategory("A", 1, 1, lastReadAsc.flag), BackupCategory("B", 2, 2, totalDesc.flag))
        sorts.backupPreferences = listOf(
            BackupPreference(libraryPreferences.sortingMode.key(), StringPreferenceValue(lastReadAsc.serialize())),
        )

        overrides(sorts, appSettings = true) shouldBe mapOf("A" to false, "B" to true)
    }

    @Test
    fun `a backup without the bit whose categories share one sort keeps them on the global`() = runTest {
        overrides(
            backup(BackupCategory("A", 1, 1, lastReadAsc.flag), BackupCategory("B", 2, 2, lastReadAsc.flag)),
        ) shouldBe mapOf("A" to false, "B" to false)
    }

    @Test
    fun `a backup marked as carrying the bit restores its flags as they are`() = runTest {
        overrides(
            backup(
                BackupCategory("A", 1, 1, lastReadAsc.flag),
                BackupCategory("B", 2, 2, totalDesc.flag),
                marked = true,
            ),
        ) shouldBe mapOf("A" to false, "B" to false)
    }

    @Test
    fun `a backup whose categories carry the bit is trusted without the marker`() = runTest {
        overrides(
            backup(
                BackupCategory("A", 1, 1, lastReadAsc.flag or CATEGORY_SORT_CUSTOMIZED),
                BackupCategory("B", 2, 2, totalDesc.flag),
            ),
        ) shouldBe mapOf("A" to true, "B" to false)
    }

    @Test
    fun `a Reikai backup from before the bit keeps a hidden category's own sort and its hidden bit`() = runTest {
        restore(
            backup(
                BackupCategory("Hidden", 1, 1, totalDesc.flag or CATEGORY_HIDDEN_MASK),
                BackupCategory("Plain", 2, 2, alphabeticalAsc.flag),
            ),
        ).single { it.name == "Hidden" }.flags shouldBe
            (totalDesc.flag or CATEGORY_HIDDEN_MASK or CATEGORY_SORT_CUSTOMIZED)
    }
}
