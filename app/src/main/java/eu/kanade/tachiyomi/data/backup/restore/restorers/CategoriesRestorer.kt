package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import reikai.domain.category.CategoryContentType
import reikai.domain.category.preferring
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.domain.library.isSortOverridden
import reikai.domain.library.markLegacySortOverride
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class CategoriesRestorer(
    private val categoryRepository: CategoryRepository,
    private val getCategories: GetCategories,
    private val libraryPreferences: LibraryPreferences,
) {

    // RK: also told how to read flags written before the sort-override bit, see legacySortGlobal
    suspend operator fun invoke(
        backupCategories: List<BackupCategory>,
        sortOverridesStored: Boolean = true,
        restoredPreferences: List<BackupPreference>? = null,
    ) {
        if (backupCategories.isEmpty()) return

        val dbCategories = getCategories.await()
        // RK: grouped, not associateBy: one name can belong to several rows (a universal category and a
        // manga one can share it), and associateBy silently kept only the last.
        val dbCategoriesByName = dbCategories.groupBy { it.name }

        val newCategories = backupCategories
            // RK: a row of the same content type matches first, then any row with that name. The
            // fallback keeps a backup made before the content type existed (every entry reads as
            // manga) matching a category the user has since made universal, instead of adding a
            // duplicate beside it. Each new row keeps the backup's content type.
            .filter { dbCategoriesByName[it.name].orEmpty().preferring(it.contentType) == null }
            .sortedBy { it.order }
        // RK --> a category spanning both libraries whose name a novel-only row already holds brings only
        // its manga half: a universal row beside it would list the name twice in the novel library, and
        // the novel restore matches the existing novel row instead.
        val novelOnlyNames = categoryRepository.getAll(CategoryContentType.NOVEL)
            .filter { it.contentType == CategoryContentType.NOVEL }
            .mapTo(HashSet()) { it.name }
        val legacyGlobal = legacySortGlobal(backupCategories, sortOverridesStored, restoredPreferences)
        categoryRepository.insertAll(
            newCategories.map { backup ->
                backup.toNewCategory().let {
                    // keeps the hidden bit
                    if (it.contentType == CategoryContentType.UNIVERSAL && it.name in novelOnlyNames) {
                        it.copy(contentType = CategoryContentType.MANGA)
                    } else {
                        it
                    }
                }.let { new ->
                    legacyGlobal?.let { new.copy(flags = markLegacySortOverride(new.flags, it)) } ?: new
                }
            },
        )
        // RK <--
        // RK: the switch moved to restoreCategorizedDisplay, which runs once both libraries' categories are in
    }

    // RK -->
    // Flags from before the override bit (Mihon's, Reikai's before 0.3.0) hold a category's own sort with
    // nothing marking it. Marked by the upgrade migration's rule, only where Mihon would turn per-category
    // sort on, the backup's sorts differing. The global is the backup's when its settings restore too, since
    // the settings restore after the categories.
    private fun legacySortGlobal(
        backupCategories: List<BackupCategory>,
        sortOverridesStored: Boolean,
        restoredPreferences: List<BackupPreference>?,
    ): LibrarySort? {
        if (sortOverridesStored || backupCategories.any { it.flags and CATEGORY_SORT_CUSTOMIZED != 0L }) return null
        if (backupCategories.distinctBy { LibrarySort.valueOf(it.flags) }.size < 2) return null
        val key = libraryPreferences.sortingMode.key()
        return (restoredPreferences?.find { it.key == key }?.value as? StringPreferenceValue)
            ?.let { LibrarySort.deserialize(it.value) }
            ?: libraryPreferences.sortingMode.get()
    }

    // Once both libraries' categories are restored, since the switch governs both. Read from the override
    // bit, not Mihon's distinct flags: the hidden bit rides in the flags, and a reset leaves the sort behind.
    suspend fun restoreCategorizedDisplay() {
        libraryPreferences.categorizedDisplaySettings.set(categoryRepository.getUnfiltered().any(::isSortOverridden))
    }
    // RK <--
}
