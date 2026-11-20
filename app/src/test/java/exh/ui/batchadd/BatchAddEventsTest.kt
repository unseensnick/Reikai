package exh.ui.batchadd

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** The batch add progress list draws every gallery it tried, whatever their messages read. */
@RunWith(RobolectricTestRunner::class)
class BatchAddEventsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `two messages whose hashes sit one apart both draw`() {
        // "Ab" hashes one above "Aa", so an index-plus-hash key gives the first and second rows the same key.
        compose.setContent {
            ComposeTestHost {
                LazyColumn { batchAddEvents(listOf(BatchAddEvent("Ab"), BatchAddEvent("Aa"))) }
            }
        }

        compose.onNodeWithText("Aa").assertExists()
    }
}
