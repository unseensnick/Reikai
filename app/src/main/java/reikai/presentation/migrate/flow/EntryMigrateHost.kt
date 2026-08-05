package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Migrating from a duplicate: adding an entry already in the library from another source offers to
 * move the old one onto it, and every surface that can hit that case (details, browse, global search,
 * history) renders this rather than repeating the dialog. The two entries are already stored, so a
 * surface raises it from its own ScreenModel's dialog state, by id, exactly as manga's `Dialog.Migrate`
 * does. That state must survive a configuration change: losing it mid-migrate detaches a running
 * commit from its dialog, which then finishes with no feedback.
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
    val request = remember(currentId, targetId) { EntryMigratePair(currentId, targetId) }
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
        // Show opens the target, which is the one being decided about; the entry being migrated
        // away is already the surface this dialog was raised from.
        onShowEntry = {
            onDismissRequest()
            target.openDetails(navigator)
        },
        onFinished = { replaced, _ -> onFinished?.invoke(replaced) ?: onDismissRequest() },
    )
}

/** The two entries this host is currently loading, so a cached model cannot serve a stale pair. */
internal data class EntryMigratePair(val currentId: Long, val targetId: Long)

internal class EntryMigrateHostScreenModel(
    contentType: ContentType,
) : StateScreenModel<EntryMigrateHostScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = migrationAdapterFor(contentType)

    fun load(request: EntryMigratePair) {
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
        val request: EntryMigratePair? = null,
        val loaded: Boolean = false,
        val entry: MigrationEntry? = null,
        val target: MigrationCandidate? = null,
    )
}
