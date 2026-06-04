package yokai.presentation.details.track

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.history.interactor.GetHistory
import yokai.domain.manga.interactor.GetManga
import yokai.domain.track.interactor.DeleteTrack
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack
import yokai.presentation.details.detailsLog

/** Which page the tracking sheet is showing. Home is the tracker list; the rest are full-page editors. */
sealed interface TrackPage {
    data object Home : TrackPage
    data class Search(val serviceId: Long) : TrackPage
    data class SetStatus(val serviceId: Long) : TrackPage
    data class SetScore(val serviceId: Long) : TrackPage
    data class SetChapters(val serviceId: Long) : TrackPage
    data class SetDate(val serviceId: Long, val isStart: Boolean) : TrackPage
    data class Remove(val serviceId: Long) : TrackPage
}

data class TrackInfoState(
    val loading: Boolean = true,
    /** Every logged-in tracker, each paired with its bound track (null when unbound). */
    val items: List<TrackItem> = emptyList(),
    val page: TrackPage = TrackPage.Home,
    val searchQuery: String = "",
    val searchResults: List<TrackSearch> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    /** Suggested start/finish date from reading history for the date editor, or null when none. */
    val suggestedDate: Long? = null,
    /**
     * Bumped on every track-table emission. [eu.kanade.tachiyomi.data.database.models.TrackImpl]'s
     * equals compares only identity (manga/sync/media id), not progress, so a status- or
     * chapter-only edit would leave the new state equal to the old one and get deduped by StateFlow,
     * showing stale values until the sheet is reopened. This forces a distinct state per write.
     */
    val revision: Int = 0,
)

/**
 * Drives the tracking sheet on the Compose details screen (Phase 4 of the details port). Mirrors
 * [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter]'s tracking ops, single-manga scope only:
 * sibling tracker-propagation is deferred to the merge port (Phase 6). The bound-track list loads
 * reactively, so every change reflected in the DB updates the sheet without a manual refetch.
 */
class TrackInfoScreenModel(
    private val mangaId: Long,
) : StateScreenModel<TrackInfoState>(TrackInfoState()), KoinComponent {

    private val getTrack: GetTrack by inject()
    private val insertTrack: InsertTrack by inject()
    private val deleteTrack: DeleteTrack by inject()
    private val getChapter: GetChapter by inject()
    private val getManga: GetManga by inject()
    private val getHistory: GetHistory by inject()
    private val trackManager: TrackManager by inject()
    private val sourceManager: SourceManager by inject()

    private var mangaTitle: String = ""
    /** The tracked manga + its source, loaded once; needed to filter and auto-match enhanced trackers. */
    private var manga: Manga? = null
    private var source: Source? = null

    init {
        screenModelScope.launchIO {
            manga = getManga.awaitById(mangaId)
            mangaTitle = manga?.title.orEmpty()
            source = manga?.let { sourceManager.getOrStub(it.source) }
            getTrack.subscribe(mangaId).collectLatest { tracks ->
                val src = source
                val items = trackManager.services
                    // Enhanced trackers (Komga/Kavita/Suwayomi) only apply to their own sources; hide
                    // them for incompatible sources, matching the legacy presenter.
                    .filter { it.isLogged && (src == null || it !is EnhancedTrackService || it.accept(src)) }
                    .map { service -> TrackItem(track = tracks.find { it.sync_id == service.id }, service = service) }
                mutableState.update { it.copy(loading = false, items = items, revision = it.revision + 1) }
            }
        }
    }

    /** Refresh every bound tracker from its remote service. Triggered when the sheet opens. */
    fun refresh() {
        screenModelScope.launchIO {
            state.value.items.forEach { item ->
                val track = item.track ?: return@forEach
                try {
                    val refreshed = item.service.refresh(track)
                    insertTrack.await(refreshed)
                    // Pull the tracker's remote read-progress into local chapters (two-way), so a
                    // refresh can mark chapters read from a bump made on the tracker's site.
                    syncChaptersWithTrackServiceTwoWay(getChapter.awaitAll(mangaId, false), refreshed, item.service)
                } catch (e: Exception) {
                    detailsLog { "track refresh failed sync=${item.service.id}: ${e.message}" }
                }
            }
        }
    }

    // --- page navigation ---

    /**
     * Add a tracker. Enhanced trackers (Komga/Kavita/Suwayomi) auto-match against the source instead
     * of prompting a search (falling back to search when no match is found); everything else opens the
     * manual search page. Mirrors the legacy presenter's enhanced-tracker bind.
     */
    fun addTracking(serviceId: Long) {
        val service = trackManager.getService(serviceId) ?: return
        val manga = manga
        if (service !is EnhancedTrackService || manga == null) {
            openSearch(serviceId)
            return
        }
        screenModelScope.launchIO {
            val match = try {
                service.match(manga)
            } catch (e: Exception) {
                detailsLog { "enhanced track match failed sync=$serviceId: ${e.message}" }
                null
            }
            if (match != null) registerTracking(serviceId, match) else openSearch(serviceId)
        }
    }

    fun openSearch(serviceId: Long) {
        mutableState.update {
            it.copy(page = TrackPage.Search(serviceId), searchQuery = mangaTitle, searchResults = emptyList(), searchError = null)
        }
        search(serviceId, mangaTitle)
    }

    fun openStatus(serviceId: Long) = mutableState.update { it.copy(page = TrackPage.SetStatus(serviceId)) }
    fun openScore(serviceId: Long) = mutableState.update { it.copy(page = TrackPage.SetScore(serviceId)) }
    fun openChapters(serviceId: Long) = mutableState.update { it.copy(page = TrackPage.SetChapters(serviceId)) }
    fun openRemove(serviceId: Long) = mutableState.update { it.copy(page = TrackPage.Remove(serviceId)) }

    fun openDate(serviceId: Long, isStart: Boolean) {
        mutableState.update { it.copy(page = TrackPage.SetDate(serviceId, isStart), suggestedDate = null) }
        screenModelScope.launchIO {
            val history = getHistory.awaitAllByMangaId(mangaId)
            val date = if (isStart) history.minOfOrNull { it.last_read } else history.maxOfOrNull { it.last_read }
            mutableState.update { it.copy(suggestedDate = date?.takeIf { d -> d > 0L }) }
        }
    }

    fun backToHome() = mutableState.update { it.copy(page = TrackPage.Home) }

    // --- search + bind ---

    fun onSearchQueryChange(query: String) = mutableState.update { it.copy(searchQuery = query) }

    fun search(serviceId: Long, query: String) {
        val service = trackManager.getService(serviceId) ?: return
        mutableState.update { it.copy(searchLoading = true, searchError = null) }
        screenModelScope.launchIO {
            try {
                val results = service.search(query)
                mutableState.update { it.copy(searchResults = results, searchLoading = false) }
            } catch (e: Exception) {
                detailsLog { "track search failed sync=$serviceId: ${e.message}" }
                mutableState.update { it.copy(searchLoading = false, searchError = e.message ?: "Search failed") }
            }
        }
    }

    fun registerTracking(serviceId: Long, item: TrackSearch, private: Boolean = false) {
        val service = trackManager.getService(serviceId) ?: return
        item.manga_id = mangaId
        item.private = private
        backToHome()
        screenModelScope.launchIO {
            val binding = try {
                service.bind(item)
            } catch (e: Exception) {
                detailsLog { "track bind failed sync=$serviceId: ${e.message}" }
                null
            }
            if (binding != null) insertTrack.await(binding)
            // No-op for normal trackers; only enhanced (Komga/Kavita/Suwayomi) sync chapters here.
            syncChaptersWithTrackServiceTwoWay(getChapter.awaitAll(mangaId, false), item, service)
        }
    }

    fun removeTracking(serviceId: Long, alsoRemoveFromService: Boolean) {
        val service = trackManager.getService(serviceId) ?: return
        val track = state.value.items.find { it.service.id == serviceId }?.track
        backToHome()
        screenModelScope.launchIO {
            deleteTrack.awaitForManga(mangaId, serviceId)
            if (alsoRemoveFromService && service.canRemoveFromService() && track != null) {
                try {
                    service.removeFromService(track)
                } catch (e: Exception) {
                    detailsLog { "removeFromService failed sync=$serviceId: ${e.message}" }
                }
            }
        }
    }

    // --- field edits ---

    fun setStatus(serviceId: Long, index: Int) {
        val item = state.value.items.find { it.service.id == serviceId } ?: return
        val track = item.track ?: return
        track.status = item.service.getStatusList()[index]
        if (item.service.isCompletedStatus(index) && track.total_chapters > 0L) {
            track.last_chapter_read = track.total_chapters.toFloat()
        }
        backToHome()
        updateRemote(track, item.service)
    }

    fun setScore(serviceId: Long, index: Int) {
        val item = state.value.items.find { it.service.id == serviceId } ?: return
        val track = item.track ?: return
        track.score = item.service.indexToScore(index)
        backToHome()
        updateRemote(track, item.service)
    }

    fun setLastChapterRead(serviceId: Long, chapterNumber: Float) {
        val item = state.value.items.find { it.service.id == serviceId } ?: return
        val track = item.track ?: return
        track.last_chapter_read = chapterNumber
        backToHome()
        updateRemote(track, item.service)
    }

    fun setDate(serviceId: Long, isStart: Boolean, date: Long) {
        val item = state.value.items.find { it.service.id == serviceId } ?: return
        val track = item.track ?: return
        if (isStart) track.started_reading_date = date else track.finished_reading_date = date
        backToHome()
        updateRemote(track, item.service)
    }

    fun setPrivate(serviceId: Long, private: Boolean) {
        val item = state.value.items.find { it.service.id == serviceId } ?: return
        val track = item.track ?: return
        track.private = private
        updateRemote(track, item.service)
    }

    private fun updateRemote(track: Track, service: TrackService) {
        // The caller mutated [track] (the live state item) in place; bump so the edit shows at once,
        // then persist. The subscription re-confirms from the DB once the remote update lands.
        mutableState.update { it.copy(revision = it.revision + 1) }
        screenModelScope.launchIO {
            val binding = try {
                service.update(track)
            } catch (e: Exception) {
                detailsLog { "track update failed sync=${service.id}: ${e.message}" }
                null
            }
            if (binding != null) insertTrack.await(binding)
        }
    }
}
