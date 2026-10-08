package eu.kanade.tachiyomi.ui.updates

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.category.CategoryContentType
import reikai.domain.category.RecentsSurface
import reikai.domain.library.CategorySortOrder
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.updates.service.UpdatesPreferences

/** The filter sheet's Reikai switches are the model's state, so a flip shows without reading a preference. */
class UpdatesSettingsViewModelTest {

    // Every model is tracked, so its category list's sharing is cancelled before Main is reset.
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension { StandardTestDispatcher() }

    private val store = EmittingPreferenceStore()
    private val system = Category(0, "", -1, 0L, CategoryContentType.UNIVERSAL)
    private val zeta = Category(1, "Zeta", 1, 0L, CategoryContentType.MANGA)
    private val alpha = Category(2, "Alpha", 2, 0L, CategoryContentType.NOVEL)
    private val table = MutableStateFlow(listOf(system, zeta, alpha))

    private fun viewModel(): UpdatesSettingsViewModel = main.track(
        UpdatesSettingsViewModel(
            updatesPreferences = UpdatesPreferences(store),
            surface = RecentsSurface.UPDATES,
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            categoryRepository = mockk<CategoryRepository> {
                every { getUnfilteredAsFlow() } returns table
                coEvery { getUnfiltered() } answers { table.value }
            },
            reikaiLibraryPreferences = ReikaiLibraryPreferences(store),
        ),
    )

    @Test
    fun `the category picker follows the category sort order`() = runTest {
        ReikaiLibraryPreferences(store).categorySortOrder.set(CategorySortOrder.A_TO_Z)
        val model = viewModel()
        backgroundScope.launch { model.categories.collect {} }
        advanceUntilIdle()

        model.categories.value shouldBe listOf(system, alpha, zeta)
    }

    @Test
    fun `a category created while the picker is open joins it`() = runTest {
        val model = viewModel()
        backgroundScope.launch { model.categories.collect {} }
        advanceUntilIdle()
        val created = Category(3, "Created", 3, 0L, CategoryContentType.UNIVERSAL)

        table.value += created
        advanceUntilIdle()

        model.categories.value shouldBe listOf(system, zeta, alpha, created)
    }

    @Test
    fun `toggling show read flips its state`() {
        val model = viewModel()
        val before = model.showRead.value

        model.toggleShowRead()

        model.showRead.value shouldBe !before
    }

    @Test
    fun `toggling group by series flips its state`() {
        val model = viewModel()
        val before = model.groupBySeries.value

        model.toggleGroupBySeries()

        model.groupBySeries.value shouldBe !before
    }

    @Test
    fun `switching the category filter on shows it on`() {
        val model = viewModel()

        model.setFilterCategories(true)

        model.filterCategories.value shouldBe true
    }
}
