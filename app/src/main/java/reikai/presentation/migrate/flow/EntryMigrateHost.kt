package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
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
    val navigator = LocalNavigator.currentOrThrow
    val request = controller.request ?: return
    val screenModel = rememberScreenModel(
        tag = "migrateHost-${request.contentType}-${request.currentId}-${request.targetId}",
    ) {
        EntryMigrateHostScreenModel(request)
    }
    val state by screenModel.state.collectAsState()

    if (!state.loaded) return
    val entry = state.entry
    val target = state.target
    if (entry == null || target == null) {
        // One of the rows vanished between the tap and the load; nothing to migrate.
        LaunchedEffect(request) { controller.dismiss() }
        return
    }
    EntryMigrateDialog(
        contentType = request.contentType,
        entry = entry,
        target = target,
        onDismissRequest = controller::dismiss,
        // Show opens the current library entry to compare against (matching the old per-type dialogs).
        onOpenTarget = {
            controller.dismiss()
            entry.openDetails(navigator)
        },
        onFinished = controller::dismiss,
    )
}

internal class EntryMigrateHostScreenModel(
    private val request: EntryMigrateController.Request,
) : StateScreenModel<EntryMigrateHostScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (request.contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    init {
        screenModelScope.launchIO {
            val entry = adapter.loadEntries(listOf(request.currentId)).firstOrNull()
            val target = adapter.storedCandidate(request.targetId)
            mutableState.update { it.copy(loaded = true, entry = entry, target = target) }
        }
    }

    data class State(
        val loaded: Boolean = false,
        val entry: MigrationEntry? = null,
        val target: MigrationCandidate? = null,
    )
}
