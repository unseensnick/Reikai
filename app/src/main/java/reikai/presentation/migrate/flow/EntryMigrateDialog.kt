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
 * screen's result tap.
 */
@Composable
fun VoyagerScreen.EntryMigrateDialog(
    contentType: ContentType,
    entry: MigrationEntry,
    target: MigrationCandidate,
    onDismissRequest: () -> Unit,
    onOpenTarget: (() -> Unit)?,
    onFinished: () -> Unit,
) {
    // Tagged per (entry, target): Voyager caches one model per host screen and tag, so without the
    // pair identity a second dialog on the same screen would reuse the first pair's model and
    // migrate onto the wrong target.
    val screenModel = rememberScreenModel(tag = "migrateDialog-${entry.id}-${target.stableKey}") {
        EntryMigrateDialogScreenModel(contentType, entry, target)
    }
    val state by screenModel.state.collectAsState()

    LaunchedEffect(Unit) {
        // Completion is a one-shot event, not state: a state flag would replay a previous success
        // when the same pair's cached model recomposes and instantly close the fresh dialog.
        screenModel.reopen()
        screenModel.finished.collect { onFinished() }
    }

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
                    OutlinedButton(onClick = { screenModel.migrate(replace = false) }) {
                        Text(text = stringResource(MR.strings.copy))
                    }
                    Button(onClick = { screenModel.migrate(replace = true) }) {
                        Text(text = stringResource(MR.strings.migrate))
                    }
                }
            }
        },
    )
}

private class EntryMigrateDialogScreenModel(
    contentType: ContentType,
    private val entry: MigrationEntry,
    private val target: MigrationCandidate,
) : StateScreenModel<EntryMigrateDialogScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    private val _finished = Channel<Unit>()
    val finished = _finished.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            val applicable = adapter.applicableFlags(listOf(entry))
            mutableState.update {
                it.copy(
                    applicableFlags = applicable,
                    // The FULL saved set: only applicable flags render, so a hidden flag's saved
                    // state survives the confirm instead of being erased from the pref.
                    selectedFlags = adapter.savedFlags(),
                )
            }
        }
    }

    /** Clear transient state from a previous appearance of this cached model. */
    fun reopen() = mutableState.update { it.copy(isMigrating = false, failed = false) }

    fun toggleFlag(flag: MigrationDataFlag) = mutableState.update {
        val selected = if (flag in it.selectedFlags) it.selectedFlags - flag else it.selectedFlags + flag
        it.copy(selectedFlags = selected)
    }

    fun migrate(replace: Boolean) {
        mutableState.update { it.copy(isMigrating = true, failed = false) }
        screenModelScope.launchIO {
            val result = runCatching {
                val resolved = adapter.resolve(target) ?: error("target failed to resolve")
                adapter.migrate(entry, resolved, replace, state.value.selectedFlags)
            }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Single-item migration failed" } }
            mutableState.update { it.copy(isMigrating = false, failed = result.isFailure) }
            if (result.isSuccess) _finished.send(Unit)
        }
    }

    data class State(
        val applicableFlags: Set<MigrationDataFlag> = emptySet(),
        val selectedFlags: Set<MigrationDataFlag> = emptySet(),
        val isMigrating: Boolean = false,
        val failed: Boolean = false,
    )
}
