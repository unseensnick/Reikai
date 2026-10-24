package reikai.presentation.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/**
 * The library's pull-to-refresh. Material reads the refreshing flag on the release frame, before the
 * update job reports running, so a spinner driven by the job alone retracts the moment it is pulled.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryUpdatePullRefreshTest {

    @get:Rule
    val compose = createComposeRule()

    private var refreshes = 0
    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    private fun showLibrary(updating: Boolean = false, startsUpdate: Boolean = true) = compose.setContent {
        ComposeTestHost {
            LibraryUpdatePullRefresh(
                updating = updating,
                enabled = true,
                onRefresh = {
                    refreshes++
                    startsUpdate
                },
            ) {
                LazyColumn(Modifier.fillMaxSize().testTag(LIST)) {
                    items(30) { Text("entry $it") }
                }
            }
        }
    }

    // A frame past the release, well inside the second the pull is held for.
    private fun pull() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(LIST).performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(FRAMES_AFTER_RELEASE)
    }

    @Test
    fun `a pull starts an update`() {
        showLibrary()

        pull()

        refreshes shouldBe 1
    }

    @Test
    fun `the spinner holds until the update reports running`() {
        showLibrary()

        pull()

        compose.onNode(spinner).assertIsDisplayed()
    }

    @Test
    fun `a refused update drops the spinner`() {
        showLibrary(startsUpdate = false)

        pull()

        compose.onNode(spinner).assertDoesNotExist()
    }

    @Test
    fun `an update already running shows the spinner`() {
        showLibrary(updating = true)

        compose.onNode(spinner).assertIsDisplayed()
    }

    private companion object {
        const val LIST = "list"
        const val FRAMES_AFTER_RELEASE = 300L
    }
}
