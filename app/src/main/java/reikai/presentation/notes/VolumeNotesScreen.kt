package reikai.presentation.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.library.ContentType

/**
 * The notes of one volume of a local series, either content type, which its page shows while that volume's
 * chip is picked. Keyed as the volume's saved order and name are, so they follow it through a move.
 */
class VolumeNotesScreen(
    private val type: ContentType,
    private val series: String,
    private val volumeKey: String,
    private val volumeLabel: String,
    private val initialNotes: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(type = type, series = series, volumeKey = volumeKey, initialNotes = initialNotes)
        }
        val notes by viewModel.notes.collectAsState()

        EntryNotesScreen(
            subtitle = volumeLabel,
            notes = notes,
            navigateUp = navigator::pop,
            onUpdate = viewModel::updateNotes,
        )
    }

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val type: ContentType,
        @Assisted private val series: String,
        @Assisted private val volumeKey: String,
        @Assisted initialNotes: String,
        @AppCoroutineScope private val appScope: CoroutineScope,
        private val volumes: LocalVolumeRepository,
    ) : ViewModel() {

        val notes: StateFlow<String>
            field = MutableStateFlow(initialNotes)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(type: ContentType, series: String, volumeKey: String, initialNotes: String): Model
        }

        fun updateNotes(content: String) {
            if (content == notes.value) return
            notes.value = content
            // On Main so the debounced saves and the one on leaving are written in order, as the series' are
            appScope.launch(Dispatchers.Main.immediate) { volumes.setNotes(type, series, volumeKey, content) }
        }
    }
}
