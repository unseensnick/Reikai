package reikai.presentation.novel.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.presentation.notes.EntryNotesScreen
import tachiyomi.core.common.util.lang.launchNonCancellable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

        val screenModel = viewModel<Model>(
            factory = Model.Factory,
            extras = CreationExtras {
                set(Model.NOVEL_ID_KEY, novelId)
                set(Model.INITIAL_NOTES_KEY, initialNotes)
            },
        )
        val state by screenModel.state.collectAsState()

        EntryNotesScreen(
            subtitle = novelTitle,
            notes = state.notes,
            navigateUp = navigator::pop,
            onUpdate = screenModel::updateNotes,
        )
    }

    private class Model(
        private val novelId: Long,
        initialNotes: String,
        private val novelRepository: NovelRepository = Injekt.get(),
    ) : StateViewModel<State>(State(initialNotes)) {

        companion object {
            val NOVEL_ID_KEY = CreationExtras.Key<Long>()
            val INITIAL_NOTES_KEY = CreationExtras.Key<String>()

            val Factory = viewModelFactory {
                initializer {
                    Model(
                        novelId = get(NOVEL_ID_KEY)!!,
                        initialNotes = get(INITIAL_NOTES_KEY)!!,
                    )
                }
            }
        }

        fun updateNotes(content: String) {
            if (content == state.value.notes) return
            mutableState.update { it.copy(notes = content) }
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
