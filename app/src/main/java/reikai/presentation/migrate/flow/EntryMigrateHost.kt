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
 * Migrating from a duplicate: adding an entry that is already in the library from another source
 * offers to move the old one onto it, and every surface that can hit that case (details, browse,
 * global search, history) shares this wiring rather than repeating the dialog.
 *
 * A surface remembers a controller, points its duplicate dialog at [EntryMigrateController.start]
 * with the two entries, and renders [EntryMigrateHost].
 */
@Stable
class EntryMigrateController {
    /** Non-null while the dialog is open, or its rows are still loading. */
    var request by mutableStateOf<Request?>(null)
        private set

    /** [currentId] is the entry already in the library; [targetId] the one just added. */
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

/**
 * The dialog for two entries already stored, by id. Surfaces that keep their own dialog state can
 * render this directly instead of holding a controller.
 */
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
    // One model per content type; the pair is loaded per appearance rather than being a constructor
    // argument, so a cached model can neither serve a stale pair nor accumulate one per pair.
    val screenModel = rememberScreenModel(tag = "migrateHost-$contentType") {
        EntryMigrateHostScreenModel(contentType)
    }
    val request = remember(contentType, currentId, targetId) {
        EntryMigrateController.Request(contentType, currentId, targetId)
    }
    LaunchedEffect(request) { screenModel.load(request) }
    val state by screenModel.state.collectAsState()

    if (!state.loaded || state.request != request) return
    val entry = state.entry
    val target = state.target
    if (entry == null || target == null) {
        // One of the two vanished between the tap and the load; say so instead of doing nothing.
        LaunchedEffect(request) {
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
        // Show opens the entry being migrated away, which is the one the user is deciding about.
        onShowEntry = {
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
            // The novel source layer has to be warm before names and covers resolve; this is often
            // the first migration surface a session touches.
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
