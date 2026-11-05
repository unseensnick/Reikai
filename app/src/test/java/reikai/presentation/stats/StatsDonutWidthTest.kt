package reikai.presentation.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.presentation.ComposeTestHost

/**
 * The Publishing status pie shows only when the charts shelf is two columns wide, judged by the shelf's own
 * width: as a tab, the navigation rail takes part of a screen wide enough for two columns on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w752dp-h1600dp")
class StatsDonutWidthTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(shelfWidth: Dp) {
        val charts = StatsCharts(
            status = mapOf(1L to 2),
            scores = emptyMap(),
            trackStatus = emptyMap(),
            lastWeek = emptyList(),
            streaks = ReadingStreaks(0, 0),
            loggedSince = null,
            groups = emptyMap(),
            mostRead = emptyList(),
            paces = emptyList(),
        )
        val cards = statsChartCards(charts, onSeeAll = {}, onOpenKey = {}).values.toList()
        compose.setContent {
            ComposeTestHost {
                Box(Modifier.width(shelfWidth)) { StatsShelfContent(StatsShelf.CHARTS, cards) }
            }
        }
    }

    @Test
    fun `a shelf two cards wide draws the pie`() {
        show(752.dp)

        compose.onNodeWithText("series").assertIsDisplayed()
    }

    @Test
    fun `a shelf too narrow for two columns leaves the pie out on a screen wide enough for them`() {
        show(600.dp)

        compose.onNodeWithText("series").assertDoesNotExist()
    }
}
