package eu.kanade.tachiyomi.ui.updates

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.category.GetNovelCategories
import reikai.domain.category.RecentsSurface
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.updates.service.UpdatesPreferences

/** The filter sheet's Reikai switches are the model's state, so a flip shows without reading a preference. */
class UpdatesSettingsViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): UpdatesSettingsViewModel {
        val store = EmittingPreferenceStore()
        return UpdatesSettingsViewModel(
            updatesPreferences = UpdatesPreferences(store),
            surface = RecentsSurface.UPDATES,
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            getCategories = mockk<GetCategories> { coEvery { await() } returns emptyList() },
            getNovelCategories = mockk<GetNovelCategories> { coEvery { await() } returns emptyList() },
        )
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
