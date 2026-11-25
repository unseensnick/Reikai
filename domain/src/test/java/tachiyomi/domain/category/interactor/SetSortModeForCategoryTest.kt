package tachiyomi.domain.category.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.library.sortForCategory
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/** The write half of the sort-override scope: per-category sort is on, so only the scope decides. */
class SetSortModeForCategoryTest {

    private val preferences = LibraryPreferences(InMemoryPreferenceStore())
        .apply { categorizedDisplaySettings.set(true) }
    private val chosen = LibrarySort(LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending)

    private val stored = mutableMapOf(
        Category.UNCATEGORIZED_ID to Category(id = Category.UNCATEGORIZED_ID, name = "", order = 0, flags = 0),
        7L to Category(id = 7L, name = "Reading", order = 1, flags = 0),
    )
    private val repository = mockk<CategoryRepository>().also { repository ->
        coEvery { repository.get(any()) } answers { stored[firstArg<Long>()] }
        coEvery { repository.updateFlags(any(), any()) } answers {
            val id = firstArg<Long>()
            stored[id] = stored.getValue(id).copy(flags = secondArg())
        }
    }
    private val setSort = SetSortModeForCategory(preferences, repository)

    @Test
    fun `sorting the universal Default category sets the global sort`() = runTest {
        setSort.await(Category.UNCATEGORIZED_ID, chosen.type, chosen.direction)

        preferences.sortingMode.get() shouldBe chosen
    }

    @Test
    fun `sorting a real category gives it its own sort`() = runTest {
        setSort.await(7L, chosen.type, chosen.direction)

        sortForCategory(stored.getValue(7L), LibrarySort.default) shouldBe chosen
    }

    @Test
    fun `sorting a hidden category keeps it hidden`() = runTest {
        stored[7L] = stored.getValue(7L).copy(flags = CATEGORY_HIDDEN_MASK)

        setSort.await(7L, chosen.type, chosen.direction)

        stored.getValue(7L).flags and CATEGORY_HIDDEN_MASK shouldBe CATEGORY_HIDDEN_MASK
    }
}
