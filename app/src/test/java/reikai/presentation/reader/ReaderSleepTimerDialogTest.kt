package reikai.presentation.reader

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.novel.tts.SleepTimer
import reikai.presentation.ComposeTestHost

/** The sleep timer as read aloud shows it: the sheet's countdown and the button's running state. */
@RunWith(RobolectricTestRunner::class)
class ReaderSleepTimerDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the minutes left count down while the sheet stays open`() {
        var minutes = 15
        compose.setContent {
            ComposeTestHost {
                ReaderSleepTimerDialog(
                    timer = SleepTimer.At(endsAt = 0L, minutes = 15),
                    minutesLeft = { minutes },
                    onOff = {},
                    onMinutes = {},
                    onEndOfChapter = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("15 minutes left").assertExists()

        minutes = 14
        compose.mainClock.advanceTimeBy(2_000)

        compose.onNodeWithText("14 minutes left").assertExists()
    }

    @Test
    fun `the sleep timer button says when a timer runs`() {
        compose.setContent {
            ComposeTestHost {
                ReadAloudControls(
                    playing = true,
                    sleepTimerActive = true,
                    onReadFromHere = {},
                    onPreviousParagraph = {},
                    onPlayPause = {},
                    onNextParagraph = {},
                    onClickSleepTimer = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Sleep timer").assertIsOn()
    }
}
