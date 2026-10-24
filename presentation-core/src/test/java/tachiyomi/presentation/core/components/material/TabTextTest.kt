package tachiyomi.presentation.core.components.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A scrollable tab row measures its tabs with no width limit, where a weighted label measured to zero
 * and the library's category tabs showed only their counts. The label stayed in the semantics tree,
 * so only its width shows the fault, read off the label itself: a Tab merges its children into one node.
 */
@RunWith(RobolectricTestRunner::class)
class TabTextTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showScrollableTab() = compose.setContent {
        MaterialTheme {
            PrimaryScrollableTabRow(selectedTabIndex = 0) {
                Tab(selected = true, onClick = {}, text = { TabText(LABEL, badgeCount = 5) })
            }
        }
    }

    @Test
    fun `a label in a scrollable row keeps its width`() {
        showScrollableTab()

        compose.onNodeWithText(LABEL, useUnmergedTree = true).assertWidthIsAtLeast(1.dp)
    }

    @Test
    fun `a label in a scrollable row keeps its count`() {
        showScrollableTab()

        compose.onNodeWithText("5", useUnmergedTree = true).assertIsDisplayed()
    }

    private companion object {
        const val LABEL = "Reading"
    }
}
