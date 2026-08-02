package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Reusable migrate-from-duplicate wiring, so every duplicate surface (details, browse, global
 * search, history) shares one migrate host instead of repeating the dialog state and render. A
 * surface holds a controller (via [rememberEntryMigrateController]), points the duplicate dialog's
 * onMigrate at [EntryMigrateController.start] with the tapped duplicate and the entry being added
 * (both already stored rows), and renders [EntryMigrateHost]. The shared [EntryMigrateDialog] then
 * carries the duplicate's state onto the target.
 */
@Stable
class EntryMigrateController {
    /** Non-null while the migrate dialog is open (or its rows are loading). */
    var request by mutableStateOf<Request?>(null)
        private set

    fun start(contentType: ContentType, currentId: Long, targetId: Long) {
        request = Request(contentType, currentId, targetId)
    }

    fun dismiss() {
        request = null
    }

    data class Request(val contentType: ContentType, val currentId: Long, val targetId: Long)
}

@Composable
fun rememberEntryMigrateController(): EntryMigrateController = remember { EntryMigrateController() }

@Composable
fun Screen.EntryMigrateHost(controller: EntryMigrateController) {
    val request = controller.request ?: return
    EntryMigrateFor(
        contentType = request.contentType,
        currentId = request.currentId,
        targetId = request.targetId,
        onDismissRequest = controller::dismiss,
    )
}

/** Render the shared migrate dialog for two already-stored rows. Call sites that keep the
 *  (current, target) pair in their own ScreenModel dialog state render this directly;
 *  [EntryMigrateHost] wraps it for the controller-based surfaces. [onFinished] (default: dismiss)
 *  receives the chosen verb so single-entry pickers can land on the target's details. */
@Composable
fun Screen.EntryMigrateFor(
    contentType: ContentType,
    currentId: Long,
    targetId: Long,
    onDismissRequest: () -> Unit,
    onFinished: ((replaced: Boolean) -> Unit)? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    // One model per content type; the pair is loaded per appearance (keyed below), never a
    // constructor arg, so a cached model can neither serve a stale pair nor accumulate per pair.
    val screenModel = rememberScreenModel(tag = "migrateHost-$contentType") {
        EntryMigrateHostScreenModel(contentType)
    }
    val request = remember(contentType, currentId, targetId) {
        EntryMigrateController.Request(contentType, currentId, targetId)
    }
    // Reload on every appearance and on every pair change: a reopened pair must commit from fresh
    // rows, not a previous open's snapshot.
    LaunchedEffect(request) { screenModel.load(request) }
    val state by screenModel.state.collectAsState()

    if (!state.loaded || state.request != request) return
    val entry = state.entry
    val target = state.target
    if (entry == null || target == null) {
        // One of the rows vanished between the tap and the load; say so instead of a silent no-op.
        LaunchedEffect(currentId, targetId) {
            context.toast(MR.strings.internal_error)
            onDismissRequest()
        }
        return
    }
    EntryMigrateDialog(
        contentType = contentType,
        entry = entry,
        target = target,
        onDismissRequest = onDismissRequest,
        // Show opens the current library entry to compare against (matching the old per-type dialogs).
        onOpenTarget = {
            onDismissRequest()
            entry.openDetails(navigator)
        },
        onFinished = onFinished ?: { onDismissRequest() },
    )
}

internal class EntryMigrateHostScreenModel(
    contentType: ContentType,
) : StateScreenModel<EntryMigrateHostScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    fun load(request: EntryMigrateController.Request) {
        mutableState.update { State(request = request) }
        screenModelScope.launchIO {
            // Warm the novel plugin host before rows are read: the duplicate-dialog route can be the
            // first migration surface entered, and a cold host loses source names and cover Referers.
            adapter.prepare()
            val entry = adapter.loadEntries(listOf(request.currentId)).firstOrNull()
            val target = adapter.storedCandidate(request.targetId)
            mutableState.update {
                if (it.request != request) return@update it
                it.copy(loaded = true, entry = entry, target = target)
            }
        }
    }

    data class State(
        val request: EntryMigrateController.Request? = null,
        val loaded: Boolean = false,
        val entry: MigrationEntry? = null,
        val target: MigrationCandidate? = null,
    )
}
