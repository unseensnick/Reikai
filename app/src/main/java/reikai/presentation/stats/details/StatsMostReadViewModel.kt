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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.StatsLibraryLoader
import reikai.presentation.stats.mostRead
import reikai.presentation.stats.statsCovers
import reikai.presentation.stats.statsDetailsScreen
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.repository.MangaRepository

/** Every series by total reading time, longest first, as the overview's Most read card ranks them. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StatsMostReadViewModel(
    private val loader: StatsLibraryLoader,
    sourcePreferences: ReikaiSourcePreferences,
    private val novelRepository: NovelRepository,
    private val mangaRepository: MangaRepository,
) : ViewModel() {

    private val library = MutableStateFlow<StatsLibrary?>(null)
    private val covers = MutableStateFlow<Map<EntryId, Any>>(emptyMap())

    /** Null while the library loads. */
    val titles: StateFlow<List<ReadTitle>?> = combine(
        library.filterNotNull(),
        sourcePreferences.statsContentType.changes(),
        covers,
    ) { library, type, covers ->
        library.titles(type).mostRead().map { ReadTitle(it.entryId, it.title, it.readDuration, covers[it.entryId]) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launchIO {
            val loaded = loader.load()
            library.value = loaded
            val ids = loaded.titles(ContentType.ALL).mostRead().map { it.entryId }
            covers.value = statsCovers(ids, loaded, mangaRepository, novelRepository)
        }
    }

    suspend fun detailsScreen(entryId: EntryId): Screen? = statsDetailsScreen(entryId, novelRepository)
}
