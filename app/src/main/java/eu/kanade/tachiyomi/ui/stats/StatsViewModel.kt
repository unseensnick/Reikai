package eu.kanade.tachiyomi.ui.stats

import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.util.fastCountNot
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import reikai.domain.category.matchesCategoryFilter
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import reikai.domain.library.smartUpdateFacts
import reikai.domain.library.smartUpdateProgressSkip
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.track.toUiTrack
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.stats.StatsSeries
import reikai.presentation.stats.statsSeries
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.source.local.isLocal

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StatsViewModel(
    private val downloadManager: DownloadManager,
    private val getLibraryManga: GetLibraryManga,
    private val getTotalReadDuration: GetTotalReadDuration,
    private val getTracks: GetTracks,
    private val preferences: LibraryPreferences,
    private val trackerManager: TrackerManager,
    // RK --> novel stats: library, tracks, read-duration, and the novel global-update prefs
    private val novelRepository: NovelRepository,
    private val getNovelTracks: GetNovelTracks,
    private val novelHistoryRepository: NovelHistoryRepository,
    private val novelPreferences: NovelPreferences,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val mangaMergeManager: MangaMergeManager,
    private val novelMergeManager: NovelMergeManager,
    // RK <--
) : ViewModel() {

    val state: StateFlow<StatsScreenState>
        field = MutableStateFlow<StatsScreenState>(StatsScreenState.Loading)

    private val loggedInTrackers by lazy { trackerManager.loggedInTrackers() }

    // RK --> All / Manga / Novels switch; flips which content's stats show (persisted)
    val contentType: StateFlow<ContentType> = sourcePreferences.statsContentType.changes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, sourcePreferences.statsContentType.get())

    fun setContentType(type: ContentType) = sourcePreferences.statsContentType.set(type)
    // RK <--

    init {
        viewModelScope.launchIO {
            val libraryManga = getLibraryManga.await()
            // RK: replaces upstream's id-distinct list. A series favorited from three sources is three
            //     rows here and one card in the library, so StatsSeries counts it once.
            val manga = mangaMergeManager.statsSeries(libraryManga) { it.id }

            // RK --> compute manga + novel ingredients once, then fold per selected type on chip change.
            val novels = novelMergeManager.statsSeries(novelRepository.getLibraryNovelAsFlow().first()) { it.id }

            val ingredients = StatsIngredients(
                mangaListRaw = libraryManga,
                manga = manga,
                novels = novels,
                // Every member's tracks, so a tracker bound on any source of a merged series counts.
                mangaTrackMap = getMangaTrackMap(manga.members),
                novelTrackMap = getNovelTrackMap(novels.members),
                mangaReadDuration = getTotalReadDuration.await(),
                novelReadDuration = novelHistoryRepository.getTotalReadDuration(),
                mangaDownloadCount = downloadManager.getDownloadCount(),
                // RK: asked of the download cache, as manga asks its own manager. The library-view row
                //     carries a hardcoded 0 (NovelMapper), filled in only by the library screen's own
                //     overlay, so reading it here reported no novel downloads at all.
                novelDownloadCount = novels.members.sumOf {
                    novelDownloadManager().getDownloadCount(it.novel)
                },
            )

            sourcePreferences.statsContentType.changes()
                .onStart { emit(sourcePreferences.statsContentType.get()) }
                .collectLatest { state.value = buildSuccess(it, ingredients) }
            // RK <--
        }
    }

    // RK --> fold the precomputed ingredients into the four cards for the selected content type.
    // mangaPart/novelPart gate which side contributes; ALL sums both.
    private fun buildSuccess(type: ContentType, i: StatsIngredients): StatsScreenState.Success {
        val mangaPart = type.includes(ContentType.MANGA)
        val novelPart = type.includes(ContentType.NOVELS)

        val overview = StatsData.Overview(
            libraryMangaCount =
            (if (mangaPart) i.manga.titles.size else 0) + (if (novelPart) i.novels.titles.size else 0),
            completedMangaCount =
            (if (mangaPart) i.manga.completedCount { it.smartUpdateFacts() } else 0) +
                (if (novelPart) i.novels.completedCount { it.smartUpdateFacts() } else 0),
            totalReadDuration =
            (if (mangaPart) i.mangaReadDuration else 0L) + (if (novelPart) i.novelReadDuration else 0L),
        )

        val titles = StatsData.Titles(
            globalUpdateItemCount =
            (if (mangaPart) getGlobalUpdateItemCount(i.mangaListRaw) else 0) +
                (if (novelPart) getNovelGlobalUpdateItemCount(i.novels.titles) else 0),
            startedMangaCount =
            (if (mangaPart) i.manga.titles.count { it.hasStarted } else 0) +
                (if (novelPart) i.novels.titles.count { it.hasStarted } else 0),
            // Novels have no local source, so local titles stays a manga-only stat.
            localMangaCount = if (mangaPart) i.manga.titles.count { it.manga.isLocal() } else 0,
        )

        val chapters = StatsData.Chapters(
            totalChapterCount =
            (if (mangaPart) i.manga.members.sumOf { it.totalChapters } else 0L).toInt() +
                (if (novelPart) i.novels.members.sumOf { it.totalChapters } else 0L).toInt(),
            readChapterCount =
            (if (mangaPart) i.manga.members.sumOf { it.readCount } else 0L).toInt() +
                (if (novelPart) i.novels.members.sumOf { it.readCount } else 0L).toInt(),
            downloadCount =
            (if (mangaPart) i.mangaDownloadCount else 0) + (if (novelPart) i.novelDownloadCount else 0),
        )

        // Per-title mean scores from both types' scored tracks. Keys are per-table ids (a manga id and a
        // novel id can coincide), so combine the value lists, not the maps.
        val scoringTrackers = loggedInTrackers.associateBy { it.id }
        val perTitleMeanScores = buildList {
            if (mangaPart) addAll(i.manga.meanScores(i.mangaTrackMap, scoringTrackers))
            if (novelPart) addAll(i.novels.meanScores(i.novelTrackMap, scoringTrackers))
        }
        val trackers = StatsData.Trackers(
            trackedTitleCount =
            (if (mangaPart) i.manga.trackedCount(i.mangaTrackMap) else 0) +
                (if (novelPart) i.novels.trackedCount(i.novelTrackMap) else 0),
            meanScore = perTitleMeanScores.average(),
            trackerCount = loggedInTrackers.size,
        )

        return StatsScreenState.Success(
            overview = overview,
            titles = titles,
            chapters = chapters,
            trackers = trackers,
        )
    }
    // RK <--

    private fun getGlobalUpdateItemCount(libraryManga: List<LibraryManga>): Int {
        // RK --> the category rule is the kernel both update jobs scope by
        val includedCategories = preferences.updateCategories.get().mapTo(mutableSetOf()) { it.toLong() }
        val excludedCategories = preferences.updateCategoriesExclude.get().mapTo(mutableSetOf()) { it.toLong() }
        // RK <--
        val updateRestrictions = preferences.autoUpdateMangaRestrictions.get()

        return libraryManga
            .filter { matchesCategoryFilter(it.categories, includedCategories, excludedCategories) } // RK
            .fastCountNot { smartUpdateProgressSkip(it.smartUpdateFacts(), updateRestrictions) != null } // RK
    }

    // RK --> the novel global-update count, over the novel update categories + restrictions and the
    // category rule both update jobs scope by
    private fun getNovelGlobalUpdateItemCount(libraryNovels: List<LibraryNovel>): Int {
        val includedCategories = novelPreferences.novelUpdateCategories().get().mapTo(mutableSetOf()) { it.toLong() }
        val excludedCategories =
            novelPreferences.novelUpdateCategoriesExclude().get().mapTo(mutableSetOf()) { it.toLong() }
        val updateRestrictions = novelPreferences.novelUpdateRestrictions().get()

        return libraryNovels.filter { matchesCategoryFilter(it.categories, includedCategories, excludedCategories) }
            .fastCountNot { smartUpdateProgressSkip(it.smartUpdateFacts(), updateRestrictions) != null }
    }
    // RK <--

    private suspend fun getMangaTrackMap(libraryManga: List<LibraryManga>): Map<Long, List<Track>> {
        val loggedInTrackerIds = loggedInTrackers.map { it.id }.toHashSet()
        return libraryManga.associate { manga ->
            val tracks = getTracks.await(manga.id)
                .fastFilter { it.trackerId in loggedInTrackerIds }

            manga.id to tracks
        }
    }

    // RK --> novel track map: convert each novel track to a manga Track (toUiTrack) so the scoring kernels
    // are shared with the manga side. Per-novel, matching the manga side's per-id map.
    private suspend fun getNovelTrackMap(libraryNovels: List<LibraryNovel>): Map<Long, List<Track>> {
        val loggedInTrackerIds = loggedInTrackers.map { it.id }.toHashSet()
        return libraryNovels.associate { novel ->
            val tracks = getNovelTracks.await(novel.id)
                .map { it.toUiTrack() }
                .fastFilter { it.trackerId in loggedInTrackerIds }

            novel.id to tracks
        }
    }
    // RK <--

    // RK: getScoredMangaTrackMap, getTrackMeanScore and get10PointScore are replaced by
    // StatsSeries.meanScores, the library's per-series mean (libraryTrackerMeans)

    // RK --> precomputed manga + novel stat ingredients, folded per content-type chip selection.
    // mangaListRaw keeps category-membership duplicates (the global-update count matches upstream over it).
    //
    // A merged series is one title, represented by its lowest-id member. That member's own status,
    // started state and local-ness are what the status stats read, so they can differ from the library
    // card, which leads on the ranked trunk. Re-deriving that ranking here would be a third copy of it.
    // The chapter totals sum every member's rows, so a merged series counts its shared chapters once
    // per source there; deduplicating those needs the match-key identities, not a group count.
    private data class StatsIngredients(
        val mangaListRaw: List<LibraryManga>,
        val manga: StatsSeries<LibraryManga>,
        val novels: StatsSeries<LibraryNovel>,
        val mangaTrackMap: Map<Long, List<Track>>,
        val novelTrackMap: Map<Long, List<Track>>,
        val mangaReadDuration: Long,
        val novelReadDuration: Long,
        val mangaDownloadCount: Int,
        val novelDownloadCount: Int,
    )
    // RK <--
}
