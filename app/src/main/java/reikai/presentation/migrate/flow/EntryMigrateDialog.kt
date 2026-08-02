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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
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
    LaunchedEffect(screenModel) {
        // Completion is a one-shot event, not state: a state flag would replay a previous success
        // when the cached model recomposes and instantly close a fresh dialog. The channel drops
        // (instead of parking) a send with no collector, so an abandoned migrate can't replay either.
        screenModel.finished.collect { onFinished(it) }
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

    // Rendezvous + trySend: delivery only to a live collector. A suspending send would park a
    // success from an abandoned dialog and replay it into the next appearance, instantly closing it.
    private val _finished = Channel<Boolean>()
    val finished = _finished.receiveAsFlow()

    /** Load the pair being shown: clears any previous appearance's transient state synchronously,
     *  then fills the applicable flags and the freshly saved selection (a commit rewrites the pref,
     *  so a cached init snapshot would go stale). */
    fun reset(pairKey: String, entry: MigrationEntry) {
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

    fun migrate(entry: MigrationEntry, target: MigrationCandidate, replace: Boolean) {
        mutableState.update { it.copy(isMigrating = true, failed = false) }
        screenModelScope.launchIO {
            val result = runCatchingCancellable {
                val resolved = adapter.resolve(target) ?: error("target failed to resolve")
                adapter.migrate(entry, resolved, replace, state.value.selectedFlags)
            }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Single-item migration failed" } }
            mutableState.update { it.copy(isMigrating = false, failed = result.isFailure) }
            if (result.isSuccess) _finished.trySend(replace)
        }
    }

    data class State(
        val pairKey: String? = null,
        val applicableFlags: Set<MigrationDataFlag> = emptySet(),
        val selectedFlags: Set<MigrationDataFlag> = emptySet(),
        val isMigrating: Boolean = false,
        val failed: Boolean = false,
    )
}
