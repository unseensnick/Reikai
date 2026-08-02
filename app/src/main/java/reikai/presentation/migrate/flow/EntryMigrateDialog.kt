package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen

/**
 * The shared single-item migrate dialog for both content types: flag checkboxes plus Show / Copy /
 * Migrate. The single-item routes have no commit bar, so the verb is chosen here, exactly as both
 * per-type dialogs do today. Opened by the duplicate-dialog routes and the single-entry search
 * screen's result tap. [onFinished] receives the chosen verb (replace = Migrate) so callers can
 * navigate accordingly.
 */
@Composable
fun VoyagerScreen.EntryMigrateDialog(
    contentType: ContentType,
    entry: MigrationEntry,
    target: MigrationCandidate,
    onDismissRequest: () -> Unit,
    onOpenTarget: (() -> Unit)?,
    onFinished: (replaced: Boolean) -> Unit,
) {
    // One model per content type (bounded, unlike a per-pair tag, which accumulated a cached model
    // for every pair ever opened on a long-lived host screen). The pair is NOT a constructor arg:
    // it is loaded per appearance below, so a cached model can never migrate a stale pair.
    val screenModel = rememberScreenModel(tag = "migrateDialog-$contentType") {
        EntryMigrateDialogScreenModel(contentType)
    }
    val state by screenModel.state.collectAsState()

    val pairKey = "${entry.id}-${target.stableKey}"
    LaunchedEffect(pairKey) { screenModel.reset(pairKey, entry) }
    // A pair switch mid-migrate deliberately drops the old pair's completion (it must not land on
    // the next pair's dialog), but the DB write DID happen: say so instead of total silence.
    val context = LocalContext.current
    LaunchedEffect(screenModel) {
        screenModel.droppedCompletions.collect { context.toast(MR.strings.migrationFlow_migratedChip) }
    }
    // Completion is state consumed exactly once. State (not a fire-and-forget channel) so a success
    // that lands while the composition is being recreated (rotation mid-migrate) is delivered on
    // re-entry instead of dropped with the dialog left re-armed; consuming it before dispatch (and
    // reset() clearing it per appearance) is what prevents the old replay-on-recompose bug.
    val finishedWith = state.finishedWith
    LaunchedEffect(finishedWith) {
        if (finishedWith != null) {
            screenModel.consumeFinished()
            onFinished(finishedWith)
        }
    }

    // A cached model briefly carries the previous pair's state until reset lands; render nothing
    // until the states line up.
    if (state.pairKey != pairKey) return

    AlertDialog(
        onDismissRequest = { if (!state.isMigrating) onDismissRequest() },
        title = { Text(text = stringResource(MR.strings.migrate)) },
        text = {
            Column {
                state.applicableFlags.forEach { flag ->
                    LabeledCheckbox(
                        label = stringResource(flag.titleRes()),
                        checked = flag in state.selectedFlags,
                        onCheckedChange = { screenModel.toggleFlag(flag) },
                    )
                }
                Text(
                    text = stringResource(MR.strings.migrationFlow_tracksNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.failed) {
                    Text(
                        text = stringResource(MR.strings.migrationFlow_rowFailed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (state.isMigrating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOpenTarget != null) {
                        TextButton(onClick = onOpenTarget) {
                            Text(text = stringResource(MR.strings.migrationFlow_showTarget))
                        }
                    }
                    OutlinedButton(onClick = { screenModel.migrate(entry, target, replace = false) }) {
                        Text(text = stringResource(MR.strings.copy))
                    }
                    Button(onClick = { screenModel.migrate(entry, target, replace = true) }) {
                        Text(text = stringResource(MR.strings.migrate))
                    }
                }
            }
        },
    )
}

private class EntryMigrateDialogScreenModel(
    contentType: ContentType,
) : StateScreenModel<EntryMigrateDialogScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    /** Fired when a pair switch dropped a SUCCESSFUL migration's completion; buffered so the send
     *  never suspends on a briefly absent collector. */
    private val _droppedCompletions = Channel<Unit>(Channel.BUFFERED)
    val droppedCompletions = _droppedCompletions.receiveAsFlow()

    /** Load the pair being shown: clears any previous appearance's transient state synchronously,
     *  then fills the applicable flags and the freshly saved selection (a commit rewrites the pref,
     *  so a cached init snapshot would go stale). A rotation re-fires this mid-migrate; the same
     *  pair's in-flight state is kept, or the wipe would re-arm the buttons over a live commit and
     *  discard the user's checkbox edits. */
    fun reset(pairKey: String, entry: MigrationEntry) {
        if (state.value.pairKey == pairKey && state.value.isMigrating) return
        mutableState.update { State(pairKey = pairKey) }
        screenModelScope.launchIO {
            adapter.prepare()
            val applicable = adapter.applicableFlags(listOf(entry))
            mutableState.update {
                if (it.pairKey != pairKey) return@update it
                it.copy(
                    applicableFlags = applicable,
                    // The FULL saved set: only applicable flags render, so a hidden flag's saved
                    // state survives the confirm instead of being erased from the pref.
                    selectedFlags = adapter.savedFlags(),
                )
            }
        }
    }

    fun toggleFlag(flag: MigrationDataFlag) = mutableState.update {
        val selected = if (flag in it.selectedFlags) it.selectedFlags - flag else it.selectedFlags + flag
        it.copy(selectedFlags = selected)
    }

    fun consumeFinished() = mutableState.update { it.copy(finishedWith = null) }

    fun migrate(entry: MigrationEntry, target: MigrationCandidate, replace: Boolean) {
        // finishedWith blocks the one-frame window between a success re-enabling the buttons and
        // the composable consuming the completion; a second tap there would migrate twice.
        if (state.value.isMigrating || state.value.finishedWith != null) return
        // Captured before the suspending resolve: a concurrent reset must not swap the flag set
        // under a commit already in flight, and a pair switch mid-migrate must not land this
        // pair's completion (or failure) on the next pair's dialog.
        val flags = state.value.selectedFlags
        val pairKey = state.value.pairKey
        mutableState.update { it.copy(isMigrating = true, failed = false) }
        screenModelScope.launchIO {
            val result = runCatchingCancellable {
                val resolved = adapter.resolve(target) ?: error("target failed to resolve")
                adapter.migrate(entry, resolved, replace, flags)
            }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Single-item migration failed" } }
            var dropped = false
            mutableState.update {
                // Idempotent flag write: the lambda may re-run on a CAS retry, but it only ever
                // sets the same boolean.
                dropped = it.pairKey != pairKey
                if (dropped) return@update it
                it.copy(
                    isMigrating = false,
                    failed = result.isFailure,
                    finishedWith = if (result.isSuccess) replace else it.finishedWith,
                )
            }
            if (dropped && result.isSuccess) _droppedCompletions.send(Unit)
        }
    }

    data class State(
        val pairKey: String? = null,
        val applicableFlags: Set<MigrationDataFlag> = emptySet(),
        val selectedFlags: Set<MigrationDataFlag> = emptySet(),
        val isMigrating: Boolean = false,
        val failed: Boolean = false,
        /** Set on success with the verb used; consumed exactly once by the composable. */
        val finishedWith: Boolean? = null,
    )
}
