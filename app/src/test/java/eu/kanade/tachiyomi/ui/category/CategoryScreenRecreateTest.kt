package eu.kanade.tachiyomi.ui.category

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.ComposeTestHost
import reikai.presentation.category.CategoryActions
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.upcoming.service.UpcomingPreferences

/** A rotation or fold recreates the Activity: the screen is disposed and drawn again over the same model. */
@RunWith(RobolectricTestRunner::class)
class CategoryScreenRecreateTest {

    @get:Rule
    val compose = createComposeRule()

    private val a = Category(id = 1L, name = "A", order = 0L, flags = 0L)
    private val table = MutableStateFlow(listOf(a))
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
            UpcomingPreferences(store),
        ),
        libraryPreferences = libraryPreferences,
        renameCategory = mockk(),
    )
    private val model = CategoryViewModel(testAppScope(), actions, reikaiLibraryPreferences)
    private val restoration = StateRestorationTester(compose)

    private fun show() {
        restoration.setContent {
            ComposeTestHost(viewModels = mapOf(CategoryViewModel::class to { model })) { CategoryScreen() }
        }
        waitFor("A")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a delete whose undo is up when the screen is recreated can still be undone`() {
        show()
        compose.runOnIdle { model.deleteCategory(a) }
        waitFor(UNDO)

        restoration.emulateSavedInstanceStateRestore()
        waitFor(UNDO)
        compose.onNodeWithText(UNDO).performClick()
        waitFor("A")

        table.value shouldBe listOf(a)
    }

    @Test
    fun `the bulk delete confirmation stays open when the screen is recreated`() {
        show()
        compose.runOnIdle { model.toggleSelection(a.id) }
        compose.onNodeWithContentDescription("Delete").performClick()
        waitFor(CONFIRM)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onAllNodesWithText(CONFIRM).fetchSemanticsNodes().size shouldBe 1
    }

    private companion object {
        const val UNDO = "Undo"
        const val CONFIRM = "Delete this category?"
    }
}
