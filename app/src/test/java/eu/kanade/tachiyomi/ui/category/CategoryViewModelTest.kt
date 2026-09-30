package eu.kanade.tachiyomi.ui.category

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.category.CategoryActions
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class CategoryViewModelTest {

    private val a = Category(id = 1L, name = "A", order = 0L, flags = 0L)
    private val b = Category(id = 2L, name = "B", order = 1L, flags = 0L)
    private val c = Category(id = 3L, name = "C", order = 2L, flags = 0L)

    private val table = MutableStateFlow(listOf(a, b, c))
    private val repository = mockk<CategoryRepository>(relaxed = true) {
        every { getUnfilteredAsFlow() } returns table
        coEvery { getUnfiltered() } answers { table.value }
        coEvery { delete(any()) } answers { table.update { rows -> rows.filterNot { it.id == firstArg<Long>() } } }
    }

    private val store = EmittingPreferenceStore()
    private val libraryPreferences = LibraryPreferences(store)
    private val reikaiLibraryPreferences = ReikaiLibraryPreferences(store)
    private val actions = CategoryActions(
        categoryRepository = repository,
        categoryIdPreferences = CategoryIdPreferences(
            libraryPreferences,
            DownloadPreferences(store),
            NovelPreferences(store),
            reikaiLibraryPreferences,
            ReikaiSourcePreferences(store),
        ),
        libraryPreferences = libraryPreferences,
        renameCategory = mockk(),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A model whose state and events are collected, as the screen collects them. */
    private fun TestScope.model(events: MutableList<CategoryEvent> = mutableListOf()): CategoryViewModel {
        val model = CategoryViewModel(actions, reikaiLibraryPreferences)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.events.collect { events += it } }
        return model
    }

    private val CategoryViewModel.selection
        get() = (state.value as CategoryScreenState.Success).selection

    /** The long press ranges from the selection's anchor, so a delete that cleared only what the screen
     *  shows left the deleted rows to come back with it. */
    @Test
    fun `a long press after undoing a bulk delete selects only the pressed row`() = runTest {
        val model = model()
        model.toggleSelection(a.id)
        model.toggleRangeSelection(b.id)
        model.deleteSelected()
        model.undoPendingDelete()

        model.toggleRangeSelection(c.id)

        model.selection shouldBe setOf(c.id)
    }
}
