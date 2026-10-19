package eu.kanade.tachiyomi.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.category.matchesCategoryFilter
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import reikai.domain.library.smartUpdateFacts
import reikai.domain.library.smartUpdateProgressSkip
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.stats.OVERVIEW_GROUPINGS
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.StatsLibraryLoader
import reikai.presentation.stats.StatsSeries
import reikai.presentation.stats.StatsSort
import reikai.presentation.stats.rows
import reikai.presentation.stats.statsCharts
import reikai.presentation.stats.statsDetailsScreen
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.source.local.isLocal
import kotlin.time.Clock

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StatsViewModel(
    private val downloadManager: DownloadManager,
    // RK: getLibraryManga, getTracksPerManga and trackerManager moved to StatsLibraryLoader
    private val getTotalReadDuration: GetTotalReadDuration,
    private val preferences: LibraryPreferences,
    // RK --> novel stats: read-duration and the novel global-update prefs; the library comes from the loader
    private val novelHistoryRepository: NovelHistoryRepository,
    private val novelPreferences: NovelPreferences,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val statsLibraryLoader: StatsLibraryLoader,
    private val novelRepository: NovelRepository,
    // RK <--
) : ViewModel() {

    val state: StateFlow<StatsScreenState>
        field = MutableStateFlow<StatsScreenState>(StatsScreenState.Loading)

    // RK: loggedInTrackers comes with the loaded library (StatsLibrary.loggedInTrackers)

    // RK --> All / Manga / Novels switch; flips which content's stats show (persisted)
    val contentType: StateFlow<ContentType> = sourcePreferences.statsContentType.changes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, sourcePreferences.statsContentType.get())

    fun setContentType(type: ContentType) = sourcePreferences.statsContentType.set(type)

    suspend fun detailsScreen(entryId: EntryId): Screen? = statsDetailsScreen(entryId, novelRepository)
    // RK <--

    init {
        viewModelScope.launchIO {
            // RK --> load both content types once, then fold per selected type on chip change.
            val library = statsLibraryLoader.load()
            val ingredients = StatsIngredients(
                library = library,
                mangaReadDuration = getTotalReadDuration.await(),
                novelReadDuration = novelHistoryRepository.getTotalReadDuration(),
                mangaDownloadCount = downloadManager.getDownloadCount(),
                // RK: asked of the download cache, as manga asks its own manager. The library-view row
                //     carries a hardcoded 0 (NovelMapper), filled in only by the library screen's own
                //     overlay, so reading it here reported no novel downloads at all.
                novelDownloadCount = library.novels.members.sumOf {
                    novelDownloadManager().getDownloadCount(it.novel)
                },
            )

            sourcePreferences.statsContentType.changes()
                .onStart { emit(sourcePreferences.statsContentType.get()) }
                .collectLatest { state.value = buildSuccess(it, ingredients) }
            // RK <--
        }
    }

    // RK --> fold the precomputed ingredients into the cards and charts for the selected content type.
    // mangaPart/novelPart gate which side contributes; ALL sums both.
    private fun buildSuccess(type: ContentType, i: StatsIngredients): StatsScreenState.Success {
        val mangaPart = type.includes(ContentType.MANGA)
        val novelPart = type.includes(ContentType.NOVELS)
        val manga = i.library.manga
        val novels = i.library.novels

        val overview = StatsData.Overview(
            libraryMangaCount =
            (if (mangaPart) manga.titles.size else 0) + (if (novelPart) novels.titles.size else 0),
            completedMangaCount =
            (if (mangaPart) manga.completedCount { it.smartUpdateFacts() } else 0) +
                (if (novelPart) novels.completedCount { it.smartUpdateFacts() } else 0),
            totalReadDuration =
            (if (mangaPart) i.mangaReadDuration else 0L) + (if (novelPart) i.novelReadDuration else 0L),
        )

        val titles = StatsData.Titles(
            globalUpdateItemCount =
            (if (mangaPart) getGlobalUpdateItemCount(manga) else 0) +
                (if (novelPart) getNovelGlobalUpdateItemCount(novels) else 0),
            startedMangaCount =
            (if (mangaPart) manga.startedCount { it.smartUpdateFacts() } else 0) +
                (if (novelPart) novels.startedCount { it.smartUpdateFacts() } else 0),
            // Novels have no local source, so local titles stays a manga-only stat.
            localMangaCount = if (mangaPart) manga.titles.count { it.manga.isLocal() } else 0,
        )

        val chapters = StatsData.Chapters(
            totalChapterCount =
            (if (mangaPart) manga.members.sumOf { it.totalChapters } else 0L).toInt() +
                (if (novelPart) novels.members.sumOf { it.totalChapters } else 0L).toInt(),
            readChapterCount =
            (if (mangaPart) manga.members.sumOf { it.readCount } else 0L).toInt() +
                (if (novelPart) novels.members.sumOf { it.readCount } else 0L).toInt(),
            downloadCount =
            (if (mangaPart) i.mangaDownloadCount else 0) + (if (novelPart) i.novelDownloadCount else 0),
        )

        // Per-title mean scores from both types' scored tracks. Keys are per-table ids (a manga id and a
        // novel id can coincide), so combine the value lists, not the maps.
        val scoringTrackers = i.library.loggedInTrackers.associateBy { it.id }
        val perTitleMeanScores = buildList {
            if (mangaPart) addAll(manga.meanScores(i.library.mangaTracks, scoringTrackers))
            if (novelPart) addAll(novels.meanScores(i.library.novelTracks, scoringTrackers))
        }
        val trackers = StatsData.Trackers(
            trackedTitleCount =
            (if (mangaPart) manga.trackedCount(i.library.mangaTracks) else 0) +
                (if (novelPart) novels.trackedCount(i.library.novelTracks) else 0),
            meanScore = perTitleMeanScores.average(),
            trackerCount = i.library.loggedInTrackers.size,
        )

        val timeZone = TimeZone.currentSystemDefault()
        return StatsScreenState.Success(
            overview = overview,
            titles = titles,
            chapters = chapters,
            trackers = trackers,
            charts = statsCharts(
                i.library.titles(type),
                i.library.sessions(type),
                OVERVIEW_GROUPINGS.associateWith { i.library.rows(it, type, StatsSort.COUNT, timeZone) },
                Clock.System.now().toLocalDateTime(timeZone).date,
                timeZone,
            ),
        )
    }
    // RK <--

    // RK: series, not rows, so a merged series counts once as In library counts it (StatsSeries.globalUpdateCount)
    private fun getGlobalUpdateItemCount(libraryManga: StatsSeries<LibraryManga>): Int {
        // RK --> the category rule is the kernel both update jobs scope by
        val includedCategories = preferences.updateCategories.get().mapTo(mutableSetOf()) { it.toLong() }
        val excludedCategories = preferences.updateCategoriesExclude.get().mapTo(mutableSetOf()) { it.toLong() }
        // RK <--
        val updateRestrictions = preferences.autoUpdateMangaRestrictions.get()

        // RK --> merged series counted once through the shared kernel
        return libraryManga.globalUpdateCount {
            matchesCategoryFilter(it.categories, includedCategories, excludedCategories) &&
                smartUpdateProgressSkip(it.smartUpdateFacts(), updateRestrictions) == null
        }
        // RK <--
    }

    // RK --> the novel global-update count, over the novel update categories + restrictions and the
    // category rule both update jobs scope by
    private fun getNovelGlobalUpdateItemCount(libraryNovels: StatsSeries<LibraryNovel>): Int {
        val includedCategories = novelPreferences.novelUpdateCategories().get().mapTo(mutableSetOf()) { it.toLong() }
        val excludedCategories =
            novelPreferences.novelUpdateCategoriesExclude().get().mapTo(mutableSetOf()) { it.toLong() }
        val updateRestrictions = novelPreferences.novelUpdateRestrictions().get()

        return libraryNovels.globalUpdateCount {
            matchesCategoryFilter(it.categories, includedCategories, excludedCategories) &&
                smartUpdateProgressSkip(it.smartUpdateFacts(), updateRestrictions) == null
        }
    }
    // RK <--

    // RK: getMangaTrackMap moved to StatsLibraryLoader, beside the novel one
    // RK: getScoredMangaTrackMap, getTrackMeanScore and get10PointScore are replaced by
    // StatsSeries.meanScores, the library's per-series mean (libraryTrackerMeans)

    // RK --> precomputed stat ingredients, folded per content-type chip selection. A merged series is
    // one title, read off the lead its library card shows (StatsSeries). The chapter totals sum every
    // member's rows, so a merged series counts its shared chapters once per source there;
    // deduplicating those needs the match-key identities, not a group count.
    private data class StatsIngredients(
        val library: StatsLibrary,
        val mangaReadDuration: Long,
        val novelReadDuration: Long,
        val mangaDownloadCount: Int,
        val novelDownloadCount: Int,
    )
    // RK <--
}
