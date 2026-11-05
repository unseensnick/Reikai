package reikai.presentation.stats

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.presentation.ComposeTestHost

/** Hiding cards while arranging Statistics, one at a time or a shelf at once. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp")
class StatsArrangeListTest {

    @get:Rule
    val compose = createComposeRule()

    private var hidden: Set<StatsCardKind>? = null

    private fun show(alreadyHidden: Set<StatsCardKind> = emptySet()) {
        compose.setContent {
            ComposeTestHost {
                StatsArrangeList(
                    layout = StatsCardKind.entries,
                    hidden = alreadyHidden,
                    contentPadding = PaddingValues(),
                    onArrange = {},
                    onHide = { hidden = it },
                )
            }
        }
    }

    @Test
    fun `tapping a card hides it`() {
        show()

        compose.onNodeWithText("Scores").performClick()

        hidden shouldBe setOf(StatsCardKind.SCORES)
    }

    @Test
    fun `tapping a shelf hides all of its cards`() {
        show()

        compose.onNodeWithText("Charts").performClick()

        hidden shouldBe StatsCardKind.entries.filter { it.shelf == StatsShelf.CHARTS }.toSet()
    }

    @Test
    fun `tapping a shelf when some of its cards are hidden shows them all`() {
        show(alreadyHidden = setOf(StatsCardKind.SCORES, StatsCardKind.MOST_READ))

        compose.onNodeWithText("Charts").performClick()

        hidden shouldBe setOf(StatsCardKind.MOST_READ)
    }

    @Test
    fun `a collapsed shelf hides its cards from the list`() {
        show()

        compose.onAllNodesWithContentDescription("Expand/collapse")[1].performClick()

        compose.onNodeWithText("Scores").assertDoesNotExist()
    }
}
