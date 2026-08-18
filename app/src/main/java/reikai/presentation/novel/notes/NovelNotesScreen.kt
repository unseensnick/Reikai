package reikai.presentation.novel.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.presentation.notes.EntryNotesScreen
import tachiyomi.core.common.util.lang.launchNonCancellable

/**
 * Full-screen markdown notes editor for a novel, the twin of
 * [eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen]. Renders through the shared [EntryNotesScreen]
 * and saves surgically via a [NovelUpdate] patch. Constructor args are primitives so the Voyager
 * screen stays serializable across state-save.
 */
class NovelNotesScreen(
    private val novelId: Long,
    private val novelTitle: String,
    private val initialNotes: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(novelId = novelId, initialNotes = initialNotes)
        }
        val state by viewModel.state.collectAsState()

        EntryNotesScreen(
            subtitle = novelTitle,
            notes = state.notes,
            navigateUp = navigator::pop,
            onUpdate = viewModel::updateNotes,
        )
    }

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val novelId: Long,
        @Assisted initialNotes: String,
        private val novelRepository: NovelRepository,
    ) : ViewModel() {

        val state: StateFlow<State>
            field = MutableStateFlow<State>(State(initialNotes))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(novelId: Long, initialNotes: String): Model
        }

        fun updateNotes(content: String) {
            if (content == state.value.notes) return
            state.update { it.copy(notes = content) }
            viewModelScope.launchNonCancellable {
                novelRepository.update(NovelUpdate(id = novelId, notes = content))
            }
        }
    }

    @Immutable
    data class State(
        val notes: String,
    )
}
