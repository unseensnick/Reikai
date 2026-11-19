package reikai.presentation.novel.notes

import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CoroutineScope
import mihon.core.metro.AppCoroutineScope
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.presentation.notes.EntryNotesScreen
import reikai.presentation.notes.NotesEditor

/**
 * Full-screen markdown notes editor for a novel, rendered through the shared [EntryNotesScreen] and edited through
 * [NotesEditor]. Saves surgically via a [NovelUpdate] patch.
 */
class NovelNotesRoute(val novelId: Long) : Screen() {

    @Composable
    override fun Content() = NovelNotesScreen(novelId)

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted novelId: Long,
        @AppCoroutineScope appScope: CoroutineScope,
        novelRepository: NovelRepository,
    ) : ViewModel() {

        /** The novel's notes, headed by its title. */
        val notes = NotesEditor(
            viewModelScope,
            appScope,
            load = { novelRepository.getById(novelId)?.let { it.title to it.notes } },
            write = { content -> novelRepository.update(NovelUpdate(novelId) { notes = content }) },
        )

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(novelId: Long): Model
        }
    }
}

@Composable
fun NovelNotesScreen(novelId: Long) {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel =
        assistedMetroViewModel<NovelNotesRoute.Model, NovelNotesRoute.Model.Factory> { create(novelId = novelId) }

    EntryNotesScreen(editor = viewModel.notes, subtitle = { it }, navigateUp = navigator::pop)
}
