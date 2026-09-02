package reikai.presentation.browse.catalogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import reikai.domain.source.SavedSearchRepository
import reikai.domain.source.SourceKey
import reikai.domain.source.model.SavedSearch
import tachiyomi.core.common.util.lang.launchIO

/**
 * One source's saved searches, for the catalogue that is showing it. Content-type neutral by
 * construction: the source is a [SourceKey], and what a search holds is a string this never reads.
 * The catalogue's own adapter is what captures a draft and applies one back.
 */
@AssistedInject
class SavedSearchViewModel(
    @Assisted private val sourceKey: SourceKey,
    private val repository: SavedSearchRepository,
) : ViewModel() {

    val state: StateFlow<List<SavedSearch>>
        field = MutableStateFlow(emptyList<SavedSearch>())

    init {
        viewModelScope.launchIO {
            repository.subscribeBySource(sourceKey).collect { searches ->
                state.update { searches }
            }
        }
    }

    /** Saves [draft] under [name]. A draft holding neither a query nor filters is not saved. */
    fun save(name: String, draft: SavedSearchDraft) {
        if (draft.isEmpty || name.isBlank()) return
        viewModelScope.launchIO {
            repository.insert(sourceKey, name.trim(), draft.query, draft.filtersJson)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launchIO { repository.delete(id) }
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceKey: SourceKey): SavedSearchViewModel
    }
}
