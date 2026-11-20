package reikai.presentation.components

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import reikai.data.local.LocalVolumeMove

/**
 * A local volume move and what follows it (its Undo, a failure toast, the hand-off to another series' screen) run
 * on a screen model's [scope] over the model's [snackbarHostState], so a rotation or fold, which rebuilds the
 * screen and keeps the model, cuts none of it off. Closing the screen ends the model, and an unanswered Undo's
 * tidy then runs at once, as [reportVolumeMove] does. A screen to open waits in [opening] for the live navigator.
 */
class VolumeMoves(private val scope: CoroutineScope, val snackbarHostState: SnackbarHostState) {

    val opening: StateFlow<NavKey?>
        field = MutableStateFlow<NavKey?>(null)

    /** Runs [block] on the model's scope; take the application context into it, never an Activity's. */
    fun launch(block: suspend VolumeMoves.() -> Unit) {
        scope.launch { block() }
    }

    suspend fun report(context: Context, move: LocalVolumeMove, message: String): Boolean =
        snackbarHostState.reportVolumeMove(context, move, message)

    /** Asks the screen to push [route], as soon as one is composed to. */
    fun open(route: NavKey) {
        opening.value = route
    }

    /** Pushes each route [open] asked for, through the navigator of the screen composed now. */
    @Composable
    fun PushOpened(push: (NavKey) -> Unit) {
        val route by opening.collectAsState()
        LaunchedEffect(route) {
            val asked = route ?: return@LaunchedEffect
            push(asked)
            opening.compareAndSet(asked, null)
        }
    }
}
