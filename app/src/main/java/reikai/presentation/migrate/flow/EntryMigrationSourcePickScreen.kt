package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.presentation.migrate.MigrationSourcePickContent
import reikai.presentation.migrate.PickMember
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The unified merge-group pick pre-step, one screen for both content types over the
 * [MigrationFlowAdapter] seam: choose which member(s) of a merged entry's group to migrate, then
 * Continue advances to [EntryMigrationConfigScreen]. Auto-forwards there when nothing in the
 * selection is merged. The UI is the shared [MigrationSourcePickContent].
 */
class EntryMigrationSourcePickScreen(
    private val contentType: ContentType,
    private val entryIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { EntryMigrationSourcePickScreenModel(contentType, entryIds) }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(state.skip) {
            if (state.skip) navigator.replace(EntryMigrationConfigScreen(contentType, entryIds))
        }
        if (state.loading || state.skip) {
            LoadingScreen()
            return
        }
        MigrationSourcePickContent(
            members = state.members,
            checked = state.checked,
            onToggle = screenModel::toggle,
            onContinue = { navigator.replace(EntryMigrationConfigScreen(contentType, state.checked.toList())) },
            navigateUp = navigator::pop,
        )
    }
}

class EntryMigrationSourcePickScreenModel(
    contentType: ContentType,
    private val entryIds: List<Long>,
) : StateScreenModel<EntryMigrationSourcePickScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    init {
        screenModelScope.launchIO {
            // Warm the source layer first (the novel plugin host): member source names and cover
            // Referers resolve against it.
            adapter.prepare()
            val members = adapter.mergeGroupMembers(entryIds)
            // No merged entries: the members are exactly the selection, so there's nothing to choose.
            if (members.map { it.id }.toSet() == entryIds.toSet()) {
                mutableState.update { it.copy(loading = false, skip = true) }
                return@launchIO
            }
            mutableState.update { it.copy(loading = false, members = members, checked = entryIds.toSet()) }
        }
    }

    fun toggle(id: Long) {
        mutableState.update {
            it.copy(checked = if (id in it.checked) it.checked - id else it.checked + id)
        }
    }

    data class State(
        val loading: Boolean = true,
        val skip: Boolean = false,
        val members: List<PickMember> = emptyList(),
        val checked: Set<Long> = emptySet(),
    )
}
