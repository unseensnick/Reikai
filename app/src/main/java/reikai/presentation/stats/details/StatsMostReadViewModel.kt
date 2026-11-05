package reikai.presentation.stats.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.stats.StatsLibrarySource
import reikai.presentation.stats.StatsReload
import reikai.presentation.stats.statsCovers
import reikai.presentation.stats.statsDetailsScreen
import tachiyomi.domain.manga.repository.MangaRepository

/** Every series by total reading time, longest first, as the overview's Most read card ranks them. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StatsMostReadViewModel(
    private val loader: StatsLibrarySource,
    sourcePreferences: ReikaiSourcePreferences,
    private val novelRepository: NovelRepository,
    private val mangaRepository: MangaRepository,
) : ViewModel() {

    private val library = StatsReload(viewModelScope, loader::load)

    // The list shows before its covers; a reload keeps the last covers until the new ones are read.
    private val covers = library.value.filterNotNull()
        .mapLatest { loaded ->
            statsCovers(loaded.mostRead(ContentType.ALL).map { it.entryId }, loaded, mangaRepository, novelRepository)
        }
        .onStart { emit(emptyMap()) }

    /** Null while the library loads. */
    val titles: StateFlow<List<ReadTitle>?> = combine(
        library.value.filterNotNull(),
        sourcePreferences.statsContentType.changes(),
        covers,
    ) { library, type, covers ->
        library.mostRead(type).map { it.copy(cover = covers[it.entryId]) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Called each time the screen comes back on screen, so it shows the library as it is now. */
    fun refresh() = library.reload()

    suspend fun detailsScreen(entryId: EntryId): Screen? = statsDetailsScreen(entryId, novelRepository)
}
