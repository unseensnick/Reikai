package reikai.presentation.migrate.flow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.CommitActivity
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.Dialog
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.State

/**
 * The migration list's modals, rendered from the state alone. Losing this block leaves the build and
 * every ViewModel test green while back and Stop go dead mid-commit, so only a render test sees it.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationListDialogsTest {

    @get:Rule
    val compose = createComposeRule()

    private var exits = 0

    private fun show(state: State) = compose.setContent {
        ComposeTestHost {
            MigrationListDialogs(
                state = state,
                onCancelCommit = {},
                onDismissDialog = {},
                onCommit = { _, _ -> },
                onExit = { exits++ },
            )
        }
    }

    @Test
    fun `an exit confirm asks before stopping`() {
        show(State(dialog = Dialog.ExitConfirm))

        compose.onNodeWithText("Stop migrating?").assertIsDisplayed()
    }

    @Test
    fun `a confirm names how many entries it migrates`() {
        show(State(dialog = Dialog.Confirm(replace = true, count = 3, untouched = 0)))

        compose.onNodeWithText("Migrate 3 entries?").assertIsDisplayed()
    }

    @Test
    fun `a running batch shows its progress`() {
        show(State(commit = CommitActivity.Batch(done = 2, total = 5)))

        compose.onNodeWithText("2 / 5").assertIsDisplayed()
    }

    @Test
    fun `an exit confirm during a commit replaces the progress window`() {
        show(State(commit = CommitActivity.Batch(done = 2, total = 5), dialog = Dialog.ExitConfirm))

        compose.onNodeWithText("2 / 5").assertDoesNotExist()
    }

    @Test
    fun `stopping runs the exit`() {
        show(State(dialog = Dialog.ExitConfirm))

        compose.onNodeWithText("Stop").performClick()

        exits shouldBe 1
    }
}
