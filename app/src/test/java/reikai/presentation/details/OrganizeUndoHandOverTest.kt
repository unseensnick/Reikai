package reikai.presentation.details

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.LocalMoveResult
import reikai.data.local.LocalVolumeMove
import reikai.domain.entry.EntryId
import reikai.presentation.ComposeTestHost

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
        every { fake.downloadProgress } returns EntryDownloadProgress.Unsupported
        every { fake.duplicateMarking } returns noDuplicateMarking()
        every { fake.volumePicking } returns picking
    }

    @Test
    fun `a move that opened this screen to organize offers its Undo here`() {
        val snackbar = SnackbarHostState()
        val move =
            mockk<LocalVolumeMove>(relaxed = true) { every { result } returns LocalMoveResult.Moved(emptyList()) }
        compose.setContent { ComposeTestHost { Navigator(DetailsContentHost(behavior, snackbar)) } }

        picking.requestOrganize(EntryId.Manga(100L), undo = VolumeMoveUndo(move, "Split out"))

        compose.waitUntil(5_000) { snackbar.currentSnackbarData != null }
        snackbar.currentSnackbarData?.visuals?.message shouldBe "Split out"
    }
}
