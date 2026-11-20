package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.singleContentType
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
@Serializable
data class EntryMigrationSourcePickRoute(
    val contentType: ContentType,
    val entryIds: List<Long>,
) : NavKey, MigrationFlowRoute {

    companion object {
        /**
         * The migration a multi-select opens, or null when it holds nothing or both content types: each
         * type migrates over its own id space, so a mixed selection has no one flow to open, and the
         * caller hides the action rather than greying it out.
         */
        fun forSelection(selection: Collection<EntryId>): EntryMigrationSourcePickRoute? {
            val type = singleContentType(selection.map { it.contentType }) ?: return null
            return EntryMigrationSourcePickRoute(type, selection.map { it.rawId })
        }
    }
}

@Composable
fun EntryMigrationSourcePickScreen(contentType: ContentType, entryIds: List<Long>) {
    val navigator = LocalNavigator.current
    val adapter = rememberMigrationAdapter(contentType)
    val viewModel =
        assistedMetroViewModel<EntryMigrationSourcePickViewModel, EntryMigrationSourcePickViewModel.Factory> {
            create(
                adapter = adapter,
                entryIds = entryIds,
            )
        }
    val state by viewModel.state.collectAsState()

    // Replaces rather than pushes, so back from the config screen returns to whatever opened the
    // migration instead of landing on a picker that decided it had nothing to ask.
    LaunchedEffect(state.forward) {
        if (state.forward) navigator.replace(EntryMigrationConfigRoute(contentType, entryIds))
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
            navigator.replace(EntryMigrationConfigRoute(contentType, state.checked.toList()))
        },
        navigateUp = navigator::pop,
    )
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
            // Null when the groups add nothing beyond what was selected, so there is no choice to make.
            val members = adapter.mergeGroupMembers(entryIds)
            if (members == null) {
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
