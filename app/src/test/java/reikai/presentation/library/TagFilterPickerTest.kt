package reikai.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.SavedTagFilter
import reikai.domain.library.TagFilter
import reikai.presentation.ComposeTestHost
import reikai.presentation.recents.EmittingPreferenceStore

/** The Filter tab's tag picker cycles a tag through shown and hidden, and the strip's chip clears it. */
@RunWith(RobolectricTestRunner::class)
class TagFilterPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = ReikaiLibraryPreferences(EmittingPreferenceStore())
    private val tagFilter = LibraryTagFilter(preferences) {
        listOf(listOf("Action", "Drama", "Slice of Life"), listOf("Action"))
    }

    private fun showPicker() {
        compose.setContent {
            ComposeTestHost { Column { TagsFilterRow(tagFilter) } }
        }
        compose.onNodeWithText("Tags").performClick()
    }

    // The picker and the strip read the library off the main thread, so their text lands a moment later.
    private fun awaitText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitTag(key: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("tag:$key").fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a first tap on a tag shows only series with it`() {
        showPicker()
        awaitTag("action")

        compose.onNodeWithTag("tag:action").performScrollTo().performClick()
        compose.waitForIdle()

        preferences.filterTagsInclude.get() shouldBe setOf("action")
    }

    @Test
    fun `a second tap on a tag hides it`() {
        showPicker()
        awaitTag("action")

        compose.onNodeWithTag("tag:action").performScrollTo().performClick()
        compose.onNodeWithTag("tag:action").performScrollTo().performClick()
        compose.waitForIdle()

        preferences.filterTagsExclude.get() shouldBe setOf("action")
    }

    @Test
    fun `the picker counts the series the filter keeps`() {
        preferences.filterTagsInclude.set(setOf("drama"))
        showPicker()
        awaitText("1 of 2 match")

        compose.onNodeWithText("1 of 2 match").assertIsDisplayed()
    }

    @Test
    fun `a tap on a saved filter applies it`() {
        preferences.savedTagFilters.set(listOf(SavedTagFilter("Mine", TagFilter(exclude = setOf("drama")))))
        showPicker()

        compose.onNodeWithTag("saved:Mine").performClick()
        compose.waitForIdle()

        preferences.filterTagsExclude.get() shouldBe setOf("drama")
    }

    @Test
    fun `a long press on a saved filter removes it once confirmed`() {
        preferences.savedTagFilters.set(listOf(SavedTagFilter("Mine", TagFilter(exclude = setOf("drama")))))
        showPicker()

        compose.onNodeWithTag("saved:Mine").performTouchInput { longClick() }
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        preferences.savedTagFilters.get() shouldBe emptyList()
    }

    @Test
    fun `the strip shows a hidden tag in the library's spelling`() {
        preferences.filterTagsExclude.set(setOf("slice of life"))
        compose.setContent { ComposeTestHost { TagFilterStrip(tagFilter) } }
        awaitText("Hiding Slice of Life")

        compose.onNodeWithText("Hiding Slice of Life").assertIsDisplayed()
    }

    @Test
    fun `a tap on a strip chip drops that tag from the filter`() {
        preferences.filterTagsInclude.set(setOf("action"))
        compose.setContent { ComposeTestHost { TagFilterStrip(tagFilter) } }

        compose.onNodeWithText("Action").performClick()
        compose.waitForIdle()

        preferences.filterTagsInclude.get() shouldBe emptySet()
    }
}
