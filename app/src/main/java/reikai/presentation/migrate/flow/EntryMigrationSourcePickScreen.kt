package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import mihon.app.di.appGraph
import reikai.domain.library.ContentType
import reikai.presentation.migrate.MigrationSourcePickContent
import reikai.presentation.migrate.PickMember
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.presentation.core.screens.LoadingScreen

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
        val context = LocalContext.current
        val viewModel =
            assistedMetroViewModel<EntryMigrationSourcePickViewModel, EntryMigrationSourcePickViewModel.Factory> {
                create(
                    adapter = context.appGraph.migrationAdapters.forType(contentType),
                    entryIds = entryIds,
                )
            }
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

@AssistedInject
class EntryMigrationSourcePickViewModel(
    @Assisted private val adapter: MigrationFlowAdapter,
    @Assisted private val entryIds: List<Long>,
) : ViewModel() {

    val state: StateFlow<EntryMigrationSourcePickViewModel.State>
        field = MutableStateFlow<EntryMigrationSourcePickViewModel.State>(State())

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(adapter: MigrationFlowAdapter, entryIds: List<Long>): EntryMigrationSourcePickViewModel
    }

    init {
        viewModelScope.launchIO {
            // The novel source layer has to be warm before member names and covers can resolve.
            adapter.prepare()
            val members = adapter.mergeGroupMembers(entryIds)
            // The group adds nothing beyond what was selected, so there is no choice to make.
            if (members.map { it.id }.toSet() == entryIds.toSet()) {
                state.update { it.copy(isLoading = false, forward = true) }
                return@launchIO
            }
            state.update {
                it.copy(isLoading = false, members = members, checked = entryIds.toSet())
            }
        }
    }

    fun toggle(id: Long) = state.update {
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
