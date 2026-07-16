package eu.kanade.tachiyomi.ui.stats

import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.fastCountNot
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import reikai.data.novel.NovelStatusCode
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.track.toUiTrack
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StatsScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getTotalReadDuration: GetTotalReadDuration = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val preferences: LibraryPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    // RK --> novel stats: library, tracks, read-duration, and the novel global-update prefs
    private val novelRepository: NovelRepository = Injekt.get(),
    private val getNovelTracks: GetNovelTracks = Injekt.get(),
    private val novelHistoryRepository: NovelHistoryRepository = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    // RK <--
) : StateScreenModel<StatsScreenState>(StatsScreenState.Loading) {

    private val loggedInTrackers by lazy { trackerManager.loggedInTrackers() }

    // RK --> All / Manga / Novels switch; flips which content's stats show (persisted)
    val contentType: StateFlow<ContentType> = sourcePreferences.statsContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, sourcePreferences.statsContentType.get())

    fun setContentType(type: ContentType) = sourcePreferences.statsContentType.set(type)
    // RK <--

    init {
        screenModelScope.launchIO {
            val libraryManga = getLibraryManga.await()
            val distinctLibraryManga = libraryManga.fastDistinctBy { it.id }

            // RK --> compute manga + novel ingredients once, then fold per selected type on chip change.
            // Novels are already one row per title (categories are aggregated), so no distinct pass.
            val distinctLibraryNovels = novelRepository.getLibraryNovelAsFlow().first().distinctBy { it.id }

            val ingredients = StatsIngredients(
                mangaListRaw = libraryManga,
                mangaList = distinctLibraryManga,
                novelList = distinctLibraryNovels,
                mangaTrackMap = getMangaTrackMap(distinctLibraryManga),
                novelTrackMap = getNovelTrackMap(distinctLibraryNovels),
                mangaReadDuration = getTotalReadDuration.await(),
                novelReadDuration = novelHistoryRepository.getTotalReadDuration(),
                mangaDownloadCount = downloadManager.getDownloadCount(),
                novelDownloadCount = distinctLibraryNovels.sumOf { it.downloadCount }.toInt(),
            )

            sourcePreferences.statsContentType.changes()
                .onStart { emit(sourcePreferences.statsContentType.get()) }
                .collectLatest { mutableState.value = buildSuccess(it, ingredients) }
            // RK <--
        }
    }

    // RK --> fold the precomputed ingredients into the four cards for the selected content type.
    // mangaPart/novelPart gate which side contributes; ALL sums both.
    private fun buildSuccess(type: ContentType, i: StatsIngredients): StatsScreenState.Success {
        val mangaPart = type != ContentType.NOVELS
        val novelPart = type != ContentType.MANGA

        val overview = StatsData.Overview(
            libraryMangaCount =
            (if (mangaPart) i.mangaList.size else 0) + (if (novelPart) i.novelList.size else 0),
            completedMangaCount =
            (
                if (mangaPart) {
                    i.mangaList.count { it.manga.status.toInt() == SManga.COMPLETED && it.unreadCount == 0L }
                } else {
                    0
                }
                ) +
                (
                    if (novelPart) {
                        i.novelList.count {
                            it.novel.status.toInt() == NovelStatusCode.COMPLETED && it.unreadCount == 0L
                        }
                    } else {
                        0
                    }
                    ),
            totalReadDuration =
            (if (mangaPart) i.mangaReadDuration else 0L) + (if (novelPart) i.novelReadDuration else 0L),
        )

        val titles = StatsData.Titles(
            globalUpdateItemCount =
            (if (mangaPart) getGlobalUpdateItemCount(i.mangaListRaw) else 0) +
                (if (novelPart) getNovelGlobalUpdateItemCount(i.novelList) else 0),
            startedMangaCount =
            (if (mangaPart) i.mangaList.count { it.hasStarted } else 0) +
                (if (novelPart) i.novelList.count { it.hasStarted } else 0),
            // Novels have no local source, so local titles stays a manga-only stat.
            localMangaCount = if (mangaPart) i.mangaList.count { it.manga.isLocal() } else 0,
        )

        val chapters = StatsData.Chapters(
            totalChapterCount =
            (if (mangaPart) i.mangaList.sumOf { it.totalChapters } else 0L).toInt() +
                (if (novelPart) i.novelList.sumOf { it.totalChapters } else 0L).toInt(),
            readChapterCount =
            (if (mangaPart) i.mangaList.sumOf { it.readCount } else 0L).toInt() +
                (if (novelPart) i.novelList.sumOf { it.readCount } else 0L).toInt(),
            downloadCount =
            (if (mangaPart) i.mangaDownloadCount else 0) + (if (novelPart) i.novelDownloadCount else 0),
        )

        // Per-title mean scores from both types' scored tracks. Keys are per-table ids (a manga id and a
        // novel id can coincide), so combine the value lists, not the maps.
        val perTitleMeanScores = buildList {
            if (mangaPart) {
                addAll(getScoredMangaTrackMap(i.mangaTrackMap).values.map { it.map(::get10PointScore).average() })
            }
            if (novelPart) {
                addAll(getScoredMangaTrackMap(i.novelTrackMap).values.map { it.map(::get10PointScore).average() })
            }
        }
        val trackers = StatsData.Trackers(
            trackedTitleCount =
            (if (mangaPart) i.mangaTrackMap.count { it.value.isNotEmpty() } else 0) +
                (if (novelPart) i.novelTrackMap.count { it.value.isNotEmpty() } else 0),
            meanScore = perTitleMeanScores.filterNot { it.isNaN() }.average(),
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
        val includedCategories = preferences.updateCategories.get().map { it.toLong() }
        val excludedCategories = preferences.updateCategoriesExclude.get().map { it.toLong() }
        val updateRestrictions = preferences.autoUpdateMangaRestrictions.get()

        return libraryManga.filter {
            val included = includedCategories.isEmpty() || it.categories.intersect(includedCategories).isNotEmpty()
            val excluded = it.categories.intersect(excludedCategories).isNotEmpty()
            included && !excluded
        }
            .fastCountNot {
                (MANGA_NON_COMPLETED in updateRestrictions && it.manga.status.toInt() == SManga.COMPLETED) ||
                    (MANGA_HAS_UNREAD in updateRestrictions && it.unreadCount != 0L) ||
                    (MANGA_NON_READ in updateRestrictions && it.totalChapters > 0 && !it.hasStarted)
            }
    }

    // RK --> novel twin of getGlobalUpdateItemCount, over the novel update categories + restrictions
    private fun getNovelGlobalUpdateItemCount(libraryNovels: List<LibraryNovel>): Int {
        val includedCategories = novelPreferences.novelUpdateCategories().get().map { it.toLong() }
        val excludedCategories = novelPreferences.novelUpdateCategoriesExclude().get().map { it.toLong() }
        val updateRestrictions = novelPreferences.novelUpdateRestrictions().get()

        return libraryNovels.filter {
            val included = includedCategories.isEmpty() || it.categories.intersect(includedCategories).isNotEmpty()
            val excluded = it.categories.intersect(excludedCategories).isNotEmpty()
            included && !excluded
        }
            .fastCountNot {
                (MANGA_NON_COMPLETED in updateRestrictions && it.novel.status.toInt() == NovelStatusCode.COMPLETED) ||
                    (MANGA_HAS_UNREAD in updateRestrictions && it.unreadCount != 0L) ||
                    (MANGA_NON_READ in updateRestrictions && it.totalChapters > 0 && !it.hasStarted)
            }
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

    // RK --> novel track map: convert each novel track to a manga Track (toUiTrack) so the scored-map +
    // score helpers below are shared with the manga side. Per-novel, matching the manga side's per-id count.
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

    private fun getScoredMangaTrackMap(mangaTrackMap: Map<Long, List<Track>>): Map<Long, List<Track>> {
        return mangaTrackMap.mapNotNull { (mangaId, tracks) ->
            val trackList = tracks.mapNotNull { track ->
                track.takeIf { it.score > 0.0 }
            }
            if (trackList.isEmpty()) return@mapNotNull null
            mangaId to trackList
        }.toMap()
    }

    private fun get10PointScore(track: Track): Double {
        val service = trackerManager.get(track.trackerId)!!
        return service.get10PointScore(track)
    }

    // RK --> precomputed manga + novel stat ingredients, folded per content-type chip selection.
    // mangaListRaw keeps category-membership duplicates (the global-update count matches upstream over it);
    // mangaList is deduped by id for every other stat.
    private data class StatsIngredients(
        val mangaListRaw: List<LibraryManga>,
        val mangaList: List<LibraryManga>,
        val novelList: List<LibraryNovel>,
        val mangaTrackMap: Map<Long, List<Track>>,
        val novelTrackMap: Map<Long, List<Track>>,
        val mangaReadDuration: Long,
        val novelReadDuration: Long,
        val mangaDownloadCount: Int,
        val novelDownloadCount: Int,
    )
    // RK <--
}
