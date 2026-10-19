package reikai.presentation.stats.details

import android.content.Context
import android.content.Intent
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
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
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
import reikai.domain.library.librarySortComparator
import reikai.domain.library.toSortMode
import reikai.domain.novel.NovelRepository
import reikai.presentation.library.MangaLibraryRows
import reikai.presentation.library.libraryTrackerMeans
import reikai.presentation.library.memberIds
import reikai.presentation.library.mixedLibraryItemSortFields
import reikai.presentation.library.novels.NovelLibraryRows
import reikai.presentation.library.withCustomInfo
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.stats.StatsGroupKey
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.StatsLibraryLoader
import reikai.presentation.stats.standingFor
import reikai.presentation.stats.titleOf
import reikai.presentation.stats.titlesIn
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The series one Statistics row counts, as the library draws them: the library's own rows, before its
 * filters and search, in the library's display mode, columns and global sort, with its continue-reading
 * button when that is on. Its filters do not apply, or the list would hold fewer series than the row counts.
 */
@AssistedInject
class StatsSeriesViewModel(
    @Assisted private val key: StatsGroupKey,
    @Assisted private val contentType: ContentType,
    private val loader: StatsLibraryLoader,
    private val mangaLibraryRows: MangaLibraryRows,
    private val novelLibraryRows: NovelLibraryRows,
    getEntryCustomInfo: GetEntryCustomInfo,
    private val novelRepository: NovelRepository,
    private val libraryPreferences: LibraryPreferences,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(key: StatsGroupKey, contentType: ContentType): StatsSeriesViewModel
    }

    private val rowsOfRow = combine(
        flow { emit(loader.load()) },
        // A type the row does not cover is never built, so it waits on nothing.
        if (contentType.includes(ContentType.MANGA)) mangaLibraryRows.rows() else flowOf(emptyList()),
        if (contentType.includes(ContentType.NOVELS)) novelLibraryRows.rows() else flowOf(emptyList()),
        getEntryCustomInfo.subscribeAll(),
    ) { library, manga, novels, customInfo ->
        val titles = library.titles(contentType)
            .titlesIn(key, TimeZone.currentSystemDefault())
            .mapTo(HashSet()) { it.entryId }
        // The display overlay the library applies at its own display read.
        library to
            (manga + novels).standingFor(titles, library::titleOf).map { it.withCustomInfo(customInfo[it.entryId]) }
    }

    /** Null while the library loads. */
    val rows: StateFlow<List<LibraryItem>?> = combine(
        rowsOfRow,
        libraryPreferences.sortingMode.changes(),
        libraryPreferences.randomSortSeed.changes(),
    ) { (library, rows), sort, seed ->
        // Lazy, so only the tracker-score sort pays for the means, as in the library.
        val means = lazy { library.trackerMeans(rows) }
        val fields = mixedLibraryItemSortFields { item -> means.value[item.entryId] ?: -1.0 }
        rows.sortedWith(librarySortComparator(sort.type.toSortMode(), sort.isAscending, seed.toLong(), fields))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val showContinueReading: StateFlow<Boolean> = libraryPreferences.showContinueReadingButton.changes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, libraryPreferences.showContinueReadingButton.get())

    fun displayMode(): PreferenceMutableState<LibraryDisplayMode> =
        libraryPreferences.displayMode.asState(viewModelScope)

    fun columnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> =
        (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(viewModelScope)

    suspend fun detailsScreen(entryId: EntryId): Screen? = when (entryId) {
        is EntryId.Manga -> MangaScreen(entryId.rawId)
        is EntryId.Novel -> NovelScreen.forStored(entryId.rawId, novelRepository)
    }

    /** The reader at the row's next chapter, as the library's continue-reading opens it; null when none. */
    suspend fun resumeIntent(context: Context, item: LibraryItem): Intent? = when (item.entryId) {
        is EntryId.Manga -> mangaLibraryRows.nextUnreadChapter(item.libraryManga.manga)
            ?.let { ReaderActivity.newIntent(context, it.mangaId, it.id) }
        is EntryId.Novel -> novelLibraryRows.resumeChapter(item.id)
            ?.let { ReaderActivity.newNovelIntent(context, it.novelId, it.id) }
    }

    // Each type's means keyed by its own row ids, as each library provider computes them.
    private fun StatsLibrary.trackerMeans(rows: List<LibraryItem>): Map<EntryId, Double> {
        val trackers = loggedInTrackers.associateBy { it.id }
        val (mangaRows, novelRows) = rows.partition { it.entryId is EntryId.Manga }
        val manga = libraryTrackerMeans(mangaRows.associate { it.id to it.memberIds() }, mangaTracks, trackers)
        val novels = libraryTrackerMeans(novelRows.associate { it.id to it.memberIds() }, novelTracks, trackers)
        return manga.mapKeys { EntryId.Manga(it.key) } + novels.mapKeys { EntryId.Novel(it.key) }
    }
}
