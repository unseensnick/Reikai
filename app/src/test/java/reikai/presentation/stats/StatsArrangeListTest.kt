package reikai.presentation.stats

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.kotest.matchers.ints.shouldBeLessThan
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
    private var hiddenFigures: Set<StatsFigureKind>? = null
    private var arrangedFigures: List<StatsFigureKind>? = null

    private fun show(
        alreadyHidden: Set<StatsCardKind> = emptySet(),
        figuresHidden: Set<StatsFigureKind> = emptySet(),
    ) {
        compose.setContent {
            ComposeTestHost {
                StatsArrangeList(
                    layout = StatsCardKind.entries,
                    hidden = alreadyHidden,
                    figureLayout = StatsFigureKind.entries,
                    hiddenFigures = figuresHidden,
                    contentPadding = PaddingValues(),
                    onArrange = {},
                    onHide = { hidden = it },
                    onArrangeFigures = { arrangedFigures = it },
                    onHideFigures = { hiddenFigures = it },
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
    fun `tapping an at a glance figure hides it`() {
        show()

        compose.onNodeWithText("Mean score").performClick()

        hiddenFigures shouldBe setOf(StatsFigureKind.MEAN_SCORE)
    }

    @Test
    fun `tapping the at a glance shelf when some figures are hidden shows them all`() {
        show(figuresHidden = setOf(StatsFigureKind.MEAN_SCORE))

        compose.onNodeWithText("At a glance").performClick()

        hiddenFigures shouldBe emptySet()
    }

    @Test
    fun `ticking a figure of a hidden overview shows the card again`() {
        show(alreadyHidden = setOf(StatsCardKind.OVERVIEW))

        compose.onNodeWithText("Mean score").performClick()

        hidden shouldBe emptySet()
    }

    @Test
    fun `dragging an at a glance figure moves it`() {
        show()

        drag("Mean score", rows = -1.5f)

        arrangedFigures!!.indexOf(StatsFigureKind.MEAN_SCORE) shouldBeLessThan StatsFigureKind.MEAN_SCORE.ordinal
    }

    /** The second shelf's cards did not change, so a stale drop there would put the first shelf back. */
    @Test
    fun `a card drag in one shelf keeps an earlier drag in another`() {
        var layout by mutableStateOf<List<StatsCardKind>>(StatsCardKind.entries)
        compose.setContent {
            ComposeTestHost {
                StatsArrangeList(
                    layout = layout,
                    hidden = emptySet(),
                    figureLayout = StatsFigureKind.entries,
                    hiddenFigures = emptySet(),
                    contentPadding = PaddingValues(),
                    onArrange = { layout = it },
                    onHide = {},
                    onArrangeFigures = {},
                    onHideFigures = {},
                )
            }
        }

        drag("Scores", rows = -3.5f)
        drag("Tags", rows = -1.5f)

        layout.first { it.shelf == StatsShelf.CHARTS } shouldBe StatsCardKind.SCORES
    }

    /** Drags [card] by its handle, at the row's end, [rows] row heights (negative is up). */
    private fun drag(card: String, rows: Float) {
        val row = compose.onNodeWithText(card)
        val height = row.fetchSemanticsNode().boundsInRoot.height
        row.performTouchInput {
            down(Offset(width - 28.dp.toPx(), centerY))
            repeat(STEPS) { moveBy(Offset(0f, rows * height / STEPS)) }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a collapsed shelf hides its cards from the list`() {
        show()

        compose.onAllNodesWithContentDescription("Expand/collapse")[1].performClick()

        compose.onNodeWithText("Scores").assertDoesNotExist()
    }

    private companion object {
        const val STEPS = 20
    }
}
