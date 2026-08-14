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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The shared single-item migrate dialog: flag checkboxes plus Copy and Migrate. Used by the routes
 * that already know both sides of the migration (the duplicate dialogs), which have no commit bar,
 * so the verb is chosen here. [onFinished] receives the verb used and the RESOLVED target (the
 * [target] argument may be pre-resolve, with no stored row behind it), so a caller can navigate
 * to what was actually migrated onto.
 */
@Composable
fun Screen.EntryMigrateDialog(
    contentType: ContentType,
    entry: MigrationEntry,
    target: MigrationCandidate,
    onDismissRequest: () -> Unit,
    onShowEntry: (() -> Unit)?,
    onFinished: (replaced: Boolean, resolved: MigrationCandidate) -> Unit,
) {
    // One model per content type: keying by pair would cache a model for every pair ever opened on
    // a long-lived screen, so the pair is loaded per appearance instead of being a constructor arg.
    // The `key` is load-bearing: without it both content types share one instance in this store.
    val viewModel = viewModel<EntryMigrateDialogViewModel>(
        key = "migrateDialog-$contentType",
        factory = EntryMigrateDialogViewModel.Factory,
        extras = CreationExtras { set(EntryMigrateDialogViewModel.CONTENT_TYPE_KEY, contentType) },
    )
    val state by viewModel.state.collectAsState()

    val pairKey = "${entry.id}-${target.key}"
    LaunchedEffect(pairKey) { viewModel.load(pairKey, entry) }

    // Completion is state consumed exactly once, not an event: a success landing while the
    // composition is being recreated (a rotation mid-migrate) is delivered on re-entry rather than
    // dropped with the dialog still armed.
    val finishedWith = state.finishedWith
    LaunchedEffect(finishedWith) {
        if (finishedWith != null) {
            viewModel.consumeFinished()
            onFinished(finishedWith.replaced, finishedWith.target)
        }
    }

    // A cached model briefly holds the previous pair until the load lands; render nothing until the
    // state and the arguments agree.
    if (state.pairKey != pairKey) return

    AlertDialog(
        onDismissRequest = { if (!state.isMigrating) onDismissRequest() },
        title = { Text(text = stringResource(MR.strings.migrate)) },
        text = {
            Column {
                MigrationFlagChecks(
                    applicable = state.applicableFlags,
                    selected = state.selectedFlags,
                    onToggle = viewModel::toggleFlag,
                )
                Text(
                    text = stringResource(MR.strings.migrationFlow_tracksNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.failed) {
                    Text(
                        text = stringResource(MR.strings.migrationFlow_commitFailed),
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
                    if (onShowEntry != null) {
                        TextButton(onClick = onShowEntry) {
                            Text(text = stringResource(MR.strings.migrationFlow_showEntry))
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.migrate(entry, target, replace = false) },
                        enabled = !state.loadingFlags,
                    ) {
                        Text(text = stringResource(MR.strings.copy))
                    }
                    Button(
                        onClick = { viewModel.migrate(entry, target, replace = true) },
                        enabled = !state.loadingFlags,
                    ) {
                        Text(text = stringResource(MR.strings.migrate))
                    }
                }
            }
        },
    )
}

internal class EntryMigrateDialogViewModel(
    contentType: ContentType,
) : ViewModel() {

    val state: StateFlow<EntryMigrateDialogViewModel.State>
        field = MutableStateFlow<EntryMigrateDialogViewModel.State>(State())

    companion object {
        val CONTENT_TYPE_KEY = CreationExtras.Key<ContentType>()

        val Factory = viewModelFactory {
            initializer { EntryMigrateDialogViewModel(contentType = get(CONTENT_TYPE_KEY)!!) }
        }
    }

    private val adapter: MigrationFlowAdapter = migrationAdapterFor(contentType)

    /** Load the pair being shown. Never loads over a live commit: a rotation re-fires this for the
     *  same pair, and a host swapping arguments mid-migrate must not wipe the running commit's
     *  state either, since that would re-arm the buttons and drop the outcome of a migration that
     *  already mutated the database. */
    fun load(pairKey: String, entry: MigrationEntry) {
        if (state.value.isMigrating) return
        state.update { State(pairKey = pairKey) }
        viewModelScope.launchIO {
            adapter.prepare()
            val applicable = adapter.applicableFlags(listOf(entry))
            val saved = adapter.savedFlags()
            state.update {
                if (it.pairKey != pairKey) return@update it
                // Seeded with the FULL saved set: only applicable flags render, so a hidden flag
                // keeps its saved state instead of being cleared for the next migration.
                it.copy(applicableFlags = applicable, selectedFlags = saved, loadingFlags = false)
            }
        }
    }

    fun toggleFlag(flag: MigrationDataFlag) = state.update {
        val selected = if (flag in it.selectedFlags) it.selectedFlags - flag else it.selectedFlags + flag
        it.copy(selectedFlags = selected)
    }

    fun consumeFinished() = state.update { it.copy(finishedWith = null) }

    fun migrate(entry: MigrationEntry, target: MigrationCandidate, replace: Boolean) {
        // finishedWith also blocks: between a success and the composable consuming it, a second tap
        // would migrate twice.
        if (state.value.isMigrating || state.value.finishedWith != null) return
        // Captured before the suspending work: a pair switch mid-migrate must not swap the flag set
        // under a live commit, nor land this pair's outcome on the next pair's dialog.
        val flags = state.value.selectedFlags
        val pairKey = state.value.pairKey
        state.update { it.copy(isMigrating = true, failed = false) }
        viewModelScope.launchIO {
            adapter.persistFlags(flags)
            val result = runCatchingCancellable { adapter.commitMigration(entry, target, replace, flags) }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Single-item migration failed" } }
            state.update {
                if (it.pairKey != pairKey) return@update it
                it.copy(
                    isMigrating = false,
                    failed = result.isFailure,
                    finishedWith = result.getOrNull()?.let { resolved -> Finished(replace, resolved) }
                        ?: it.finishedWith,
                )
            }
        }
    }

    /** The verb used and what [MigrationFlowAdapter.commitMigration] actually migrated onto. */
    data class Finished(val replaced: Boolean, val target: MigrationCandidate)

    data class State(
        val pairKey: String? = null,
        val applicableFlags: Set<MigrationDataFlag> = emptySet(),
        val selectedFlags: Set<MigrationDataFlag> = emptySet(),
        /** True until the flag scan lands. The verbs stay disabled meanwhile: committing before it
         *  arrives migrates with an empty set the user never saw AND persists it, so the next
         *  migration silently carries nothing either. The batch confirm dialog has the same gate. */
        val loadingFlags: Boolean = true,
        val isMigrating: Boolean = false,
        val failed: Boolean = false,
        /** Set on success; consumed exactly once by the composable. */
        val finishedWith: Finished? = null,
    )
}
