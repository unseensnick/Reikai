package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import reikai.domain.library.ContentType
import reikai.presentation.migrate.MigrationSourcePickContent
import reikai.presentation.migrate.PickMember
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The pre-step for migrating a merged entry: choose which of its grouped sources to migrate.
 *
 * An entry can be merged from several sources, and migrating one of them is a different act from
 * migrating them all, so the choice is asked before anything is searched. Nothing merged in the
 * selection means nothing to choose, and the screen forwards itself to [EntryMigrationConfigScreen].
 */
class EntryMigrationSourcePickScreen(
    private val contentType: ContentType,
    private val entryIds: List<Long>,
) : Screen(), MigrationFlowScreen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<EntryMigrationSourcePickViewModel>(
            factory = EntryMigrationSourcePickViewModel.Factory,
            extras = CreationExtras {
                set(EntryMigrationSourcePickViewModel.CONTENT_TYPE_KEY, contentType)
                set(EntryMigrationSourcePickViewModel.ENTRY_IDS_KEY, entryIds)
            },
        )
        val state by viewModel.state.collectAsState()

        // Replaces rather than pushes, so back from the config screen returns to whatever opened the
        // migration instead of landing on a picker that decided it had nothing to ask.
        LaunchedEffect(state.forward) {
            if (state.forward) navigator.replace(EntryMigrationConfigScreen(contentType, entryIds))
        }
        if (state.isLoading || state.forward) {
            LoadingScreen()
            return
        }

        MigrationSourcePickContent(
            members = state.members,
            checked = state.checked,
            onToggle = viewModel::toggle,
            onClickCover = { it.openDetails(navigator) },
            onContinue = {
                navigator.replace(EntryMigrationConfigScreen(contentType, state.checked.toList()))
            },
            navigateUp = navigator::pop,
        )
    }
}

class EntryMigrationSourcePickViewModel(
    contentType: ContentType,
    private val entryIds: List<Long>,
) : StateViewModel<EntryMigrationSourcePickViewModel.State>(State()) {

    companion object {
        val CONTENT_TYPE_KEY = CreationExtras.Key<ContentType>()
        val ENTRY_IDS_KEY = CreationExtras.Key<List<Long>>()

        val Factory = viewModelFactory {
            initializer {
                EntryMigrationSourcePickViewModel(
                    contentType = get(CONTENT_TYPE_KEY)!!,
                    entryIds = get(ENTRY_IDS_KEY)!!,
                )
            }
        }
    }

    private val adapter: MigrationFlowAdapter = migrationAdapterFor(contentType)

    init {
        viewModelScope.launchIO {
            // The novel source layer has to be warm before member names and covers can resolve.
            adapter.prepare()
            val members = adapter.mergeGroupMembers(entryIds)
            // The group adds nothing beyond what was selected, so there is no choice to make.
            if (members.map { it.id }.toSet() == entryIds.toSet()) {
                mutableState.update { it.copy(isLoading = false, forward = true) }
                return@launchIO
            }
            mutableState.update {
                it.copy(isLoading = false, members = members, checked = entryIds.toSet())
            }
        }
    }

    fun toggle(id: Long) = mutableState.update {
        it.copy(checked = if (id in it.checked) it.checked - id else it.checked + id)
    }

    data class State(
        val isLoading: Boolean = true,
        /** Nothing merged: hand straight over to the source picker. */
        val forward: Boolean = false,
        val members: List<PickMember> = emptyList(),
        val checked: Set<Long> = emptySet(),
    )
}
