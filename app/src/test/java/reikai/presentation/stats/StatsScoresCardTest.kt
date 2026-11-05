package reikai.presentation.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.presentation.ComposeTestHost

/** The Scores card as a ratings breakdown: a row per score, and a tap on one lists that score's series. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class StatsScoresCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<StatsGroupKey>()

    // Three series at 9 and one at 6, so 6 is only ever the label of its own row.
    private fun show() {
        val charts = StatsCharts(
            status = emptyMap(),
            scores = mapOf(9 to 3, 6 to 1),
            trackStatus = emptyMap(),
            lastWeek = emptyList(),
            streaks = ReadingStreaks(0, 0),
            loggedSince = null,
            groups = emptyMap(),
            mostRead = emptyList(),
            paces = emptyList(),
        )
        val card = statsChartCards(charts, onSeeAll = {}, onOpenKey = { opened += it }).values.single()
        compose.setContent {
            ComposeTestHost {
                Column { card(Modifier) }
            }
        }
    }

    @Test
    fun `a score's row lists that score's series`() {
        show()

        compose.onNodeWithText("6").performClick()

        opened shouldBe listOf(StatsGroupKey.Score(6))
    }
}
