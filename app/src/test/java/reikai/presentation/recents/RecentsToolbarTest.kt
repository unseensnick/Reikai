package reikai.presentation.recents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/**
 * The Recents bar in and out of selection. Mihon's AppBar drops its normal actions once the counter
 * rises, so a selection action handed to the wrong slot vanishes with the build green.
 */
@RunWith(RobolectricTestRunner::class)
class RecentsToolbarTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun showBar(selectionCount: Int) = compose.setContent {
        ComposeTestHost {
            RecentsToolbar(
                title = "History",
                query = null,
                onQueryChange = {},
                selectionCount = selectionCount,
                onCancelSelection = { calls += "cancel" },
                onSelectAll = { calls += "all" },
                onInvertSelection = { calls += "invert" },
                filterActive = false,
                onFilterClicked = { calls += "filter" },
                showsCalendar = false,
                onCalendarClicked = {},
                showsRefresh = false,
                onRefresh = {},
                showsClearHistory = false,
                onClearHistory = {},
            )
        }
    }

    @Test
    fun `a selection offers select all`() {
        showBar(selectionCount = 2)

        compose.onNodeWithContentDescription("Select all").performClick()

        calls shouldBe listOf("all")
    }

    @Test
    fun `a selection offers select inverse`() {
        showBar(selectionCount = 2)

        compose.onNodeWithContentDescription("Select inverse").performClick()

        calls shouldBe listOf("invert")
    }

    @Test
    fun `a selection can be cancelled`() {
        showBar(selectionCount = 2)

        compose.onNodeWithContentDescription("Cancel").performClick()

        calls shouldBe listOf("cancel")
    }

    @Test
    fun `with nothing selected the bar offers the filter`() {
        showBar(selectionCount = 0)

        compose.onNodeWithContentDescription("Filter").assertIsDisplayed()
    }
}
