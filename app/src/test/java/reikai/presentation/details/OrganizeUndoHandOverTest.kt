package reikai.presentation.details

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.LocalMoveResult
import reikai.data.local.LocalVolumeMove
import reikai.domain.entry.EntryId
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.DownloadProgress

/**
 * A split or a library combine with Also organize files ticked opens the other series' screen, which ends the
 * snackbar on the screen it left; the screen it opens offers the move's Undo instead. Both types share the screen.
 */
@RunWith(RobolectricTestRunner::class)
class OrganizeUndoHandOverTest {

    @get:Rule
    val compose = createComposeRule()

    private val picking = noVolumePicking()

    private val behavior = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
        every { fake.state } returns MutableStateFlow(loadedDetails())
        every { fake.downloadProgress(any()) } returns DownloadProgress.Unsupported
        every { fake.duplicateMarking } returns noDuplicateMarking()
        every { fake.volumePicking } returns picking
    }

    @Test
    fun `a move that opened this screen to organize offers its Undo here`() {
        val snackbar = SnackbarHostState()
        val move =
            mockk<LocalVolumeMove>(relaxed = true) { every { result } returns LocalMoveResult.Moved }
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior, snackbar).Content() } }

        picking.requestOrganize(EntryId.Manga(100L), undo = VolumeMoveUndo(move, "Split out"))

        compose.waitUntil(5_000) { snackbar.currentSnackbarData != null }
        snackbar.currentSnackbarData?.visuals?.message shouldBe "Split out"
    }

    @Test
    fun `the handed-over snackbar's action is Undo`() {
        val snackbar = handedOver(movedMove())

        snackbar.currentSnackbarData?.visuals?.actionLabel shouldBe "Undo"
    }

    @Test
    fun `Undo on the handed-over snackbar undoes the move rather than finishing it`() {
        val move = movedMove()
        val snackbar = handedOver(move)

        snackbar.currentSnackbarData?.performAction()

        coVerify(timeout = 5_000) { move.undo() }
        verify(exactly = 0) { move.finish() }
    }

    /** A rotation or fold disposes the screen and draws it again over the same model, which keeps the Undo. */
    @Test
    fun `the handed-over Undo survives the screen being recreated`() {
        val move = movedMove()
        val snackbar = SnackbarHostState()
        val host = DetailsContentHost(behavior, snackbar)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ComposeTestHost { host.Content() } }
        picking.requestOrganize(EntryId.Manga(100L), undo = VolumeMoveUndo(move, "Split out"))
        compose.waitUntil(5_000) { snackbar.currentSnackbarData != null }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { snackbar.currentSnackbarData != null }
        snackbar.currentSnackbarData?.performAction()

        coVerify(timeout = 5_000) { move.undo() }
        verify(exactly = 0) { move.finish() }
    }

    private fun movedMove() = mockk<LocalVolumeMove>(relaxed = true) {
        every { result } returns LocalMoveResult.Moved
        coEvery { undo() } returns LocalMoveResult.Moved
    }

    /** This screen, opened by [move]'s request to organize, once it shows the move's snackbar. */
    private fun handedOver(move: LocalVolumeMove): SnackbarHostState {
        val snackbar = SnackbarHostState()
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior, snackbar).Content() } }
        picking.requestOrganize(EntryId.Manga(100L), undo = VolumeMoveUndo(move, "Split out"))
        compose.waitUntil(5_000) { snackbar.currentSnackbarData != null }
        return snackbar
    }
}
