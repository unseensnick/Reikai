package reikai.presentation.stats.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.stats.StatsDimension
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.StatsLibraryLoader
import reikai.presentation.stats.StatsRow
import reikai.presentation.stats.StatsSort
import reikai.presentation.stats.rows
import tachiyomi.core.common.util.lang.launchIO

@Immutable
data class StatsGroupState(val contentType: ContentType, val sort: StatsSort, val rows: List<StatsRow>)

/** One grouping's full list, for the content type the overview has selected. */
@AssistedInject
class StatsGroupViewModel(
    @Assisted private val dimension: StatsDimension,
    private val loader: StatsLibraryLoader,
    sourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(dimension: StatsDimension): StatsGroupViewModel
    }

    private val library = MutableStateFlow<StatsLibrary?>(null)
    private val sort = MutableStateFlow(StatsSort.COUNT)

    /** Null while the library loads. */
    val state: StateFlow<StatsGroupState?> = combine(
        library.filterNotNull(),
        sourcePreferences.statsContentType.changes(),
        sort,
    ) { library, type, sort ->
        StatsGroupState(type, sort, library.rows(dimension, type, sort, TimeZone.currentSystemDefault()))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launchIO { library.value = loader.load() }
    }

    fun setSort(sort: StatsSort) {
        this.sort.value = sort
    }
}
