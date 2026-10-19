package reikai.presentation.stats.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import reikai.domain.novel.NovelRepository
import reikai.presentation.library.MangaLibraryRows
import reikai.presentation.library.novels.NovelLibraryRows
import reikai.presentation.library.withCustomInfo
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.stats.StatsGroupKey
import reikai.presentation.stats.StatsLibraryLoader
import reikai.presentation.stats.standingFor
import reikai.presentation.stats.titleOf
import reikai.presentation.stats.titlesIn

/**
 * The series one Statistics row counts, as the library draws them. The rows are the library's own, before
 * its filters and search, so they carry the same badges and merged counts, and stay current as they change.
 */
@AssistedInject
class StatsSeriesViewModel(
    @Assisted private val key: StatsGroupKey,
    @Assisted private val contentType: ContentType,
    private val loader: StatsLibraryLoader,
    mangaLibraryRows: MangaLibraryRows,
    novelLibraryRows: NovelLibraryRows,
    getEntryCustomInfo: GetEntryCustomInfo,
    private val novelRepository: NovelRepository,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(key: StatsGroupKey, contentType: ContentType): StatsSeriesViewModel
    }

    /** Null while the library loads. A type the row does not cover is never built, so it waits on nothing. */
    val rows: StateFlow<List<LibraryItem>?> = combine(
        flow { emit(loader.load()) },
        if (contentType.includes(ContentType.MANGA)) mangaLibraryRows.rows() else flowOf(emptyList()),
        if (contentType.includes(ContentType.NOVELS)) novelLibraryRows.rows() else flowOf(emptyList()),
        getEntryCustomInfo.subscribeAll(),
    ) { library, manga, novels, customInfo ->
        val titles = library.titles(contentType)
            .titlesIn(key, TimeZone.currentSystemDefault())
            .mapTo(HashSet()) { it.entryId }
        (manga + novels)
            .standingFor(titles, library::titleOf)
            // The display overlay the library applies at its own display read.
            .map { it.withCustomInfo(customInfo[it.entryId]) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.libraryManga.manga.title })
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun detailsScreen(entryId: EntryId): Screen? = when (entryId) {
        is EntryId.Manga -> MangaScreen(entryId.rawId)
        is EntryId.Novel -> NovelScreen.forStored(entryId.rawId, novelRepository)
    }
}
