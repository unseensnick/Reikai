package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.Test
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The migrator stamps its version only after the whole chain resolves, so a kill later in the chain
 * runs this again, after the user may already have reset a category to follow the global sort.
 */
class SetupCategorySortOverrideMigrationTest {

    private val store = EmittingPreferenceStore()
    private val libraryPreferences = LibraryPreferences(store).apply { categorizedDisplaySettings.set(true) }

    private var flags = LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending).flag
    private val categories = mockk<CategoryRepository> {
        coEvery { getAll(any()) } answers { listOf(Category(id = 1L, name = "C", order = 0L, flags = flags)) }
        coEvery { updateFlags(1L, any<Long>()) } answers { flags = secondArg() }
    }
    private val migration = SetupCategorySortOverrideMigration(libraryPreferences, categories, store)

    @Test
    fun `a second run keeps a category the user reset to the global sort`() = runTest {
        migration.invoke(MigrationContext(dryrun = false, previousVersion = 182))
        // Reset to global clears only the override bit and leaves the old sort in the flags.
        flags = flags and CATEGORY_SORT_CUSTOMIZED.inv()

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 182))

        (flags and CATEGORY_SORT_CUSTOMIZED) shouldBe 0L
    }
}
