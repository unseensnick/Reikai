package reikai.presentation.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.readinglog.EntryReadingSession
import reikai.domain.readinglog.ReadingPace
import reikai.presentation.ComposeTestHost
import kotlin.time.Instant

/**
 * The reading card under its streaks, in a body taller than it needs, as a shelf stretches it to the
 * tallest card in its row. A row that takes all the height it is given leaves the lines under it none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp")
class StatsReadingCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate(2026, 10, 16)

    private fun show() {
        val session = EntryReadingSession(1L, "", Instant.parse("2026-10-16T12:00:00Z"), 300_000L)
        val charts = statsCharts(
            titles = emptyList(),
            sessions = listOf(TitleReadingSession(EntryId.Manga(1L), "", session)),
            mostRead = emptyList(),
            paces = listOf(
                StatsPace(ContentType.MANGA, ReadingPace.Known(4_000.0)),
                StatsPace(ContentType.NOVELS, ReadingPace.Known(250.0)),
            ),
            groups = emptyMap(),
            today = today,
            timeZone = TimeZone.UTC,
        )
        val card = statsReadingCard(charts, onOpenReading = {})
        compose.setContent {
            ComposeTestHost {
                Column { card(Modifier.height(900.dp)) }
            }
        }
    }

    @Test
    fun `the card shows the time read today`() {
        show()

        compose.onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun `the card shows each type's pace`() {
        show()

        compose.onNodeWithText("Words per minute").assertIsDisplayed()
    }

    @Test
    fun `the card shows when logging began`() {
        show()

        compose.onNodeWithText("Logged since", substring = true).assertIsDisplayed()
    }
}
