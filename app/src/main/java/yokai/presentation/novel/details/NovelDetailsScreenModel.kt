package yokai.presentation.novel.details

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import co.touchlab.kermit.Logger
import java.net.URL
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithNovelSource
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.data.DatabaseHandler
import yokai.data.novel.NovelStatusCode
import yokai.data.novel.toNovel
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.SetNovelCategories
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.novel.source.NovelSource
import yokai.novel.text.htmlToParagraphs
import yokai.presentation.novel.browse.ChapterRead

/**
 * Database-first details for a SAVED (library) novel (Phase 7). The chapter list comes from the DB
 * via a reactive Flow, so a saved novel opens offline and shows read/bookmark from stored rows; the
 * source plugin is hit only on the first-ever open (nothing stored) or an explicit [refresh]. New
 * chapters arrive in the background via `NovelUpdateJob` and surface through the Flow, mirroring the
 * manga side (details never fetch on open).
 *
 * The plugin host needs an Activity `Context` and the ScreenModel outlives configuration changes, so
 * source resolution + the host live in the composable; it hands the resolved [NovelSource] here via
 * [onSourceReady]. The DB-first read needs no source; only first-open / refresh / chapter-text do.
 */
class NovelDetailsScreenModel(
    private val sourceId: String,
    private val novelUrl: String,
) : StateScreenModel<NovelDetailsState>(NovelDetailsState.Loading) {

    private val novelRepo: NovelRepository by injectLazy()
    private val chapterRepo: NovelChapterRepository by injectLazy()
    private val handler: DatabaseHandler by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()

    /** Resolved by the screen once the plugin host loads it; null until then. Source-dependent ops
     *  (first-open fetch, refresh, chapter text) no-op or defer until it's set. */
    @Volatile
    private var source: NovelSource? = null

    /** A library novel already has its chapters synced at favorite time, so the first-open fetch is
     *  a rare fallback; guard it so it runs at most once. */
    private var firstFetchTried = false
    private var refreshJob: Job? = null

    /** Range-select anchors [min, max] into the displayed chapter order; -1 when no selection.
     *  Mirrors the manga side so a long-press extends the range. */
    private val selectedPositions = intArrayOf(-1, -1)

    init {
        observeFromDb()
    }

    // DB-first: the novel Flow drives an inner chapter Flow; every emission rebuilds Loaded from
    // stored rows. No source call on this path.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFromDb() {
        screenModelScope.launchIO {
            novelRepo.getByUrlAndSourceAsFlow(novelUrl, sourceId)
                .flatMapLatest { novel ->
                    val id = novel?.id
                    if (id == null) flowOf(novel to emptyList())
                    else chapterRepo.getByNovelIdAsFlow(id).map { chapters -> novel to chapters }
                }
                .collectLatest { (novel, chapters) ->
                    if (novel == null) {
                        // Not in the DB (unexpected for a library entry); needs a first-open fetch
                        // once the source resolves. Keep any already-loaded state visible.
                        maybeFirstFetch(null)
                        return@collectLatest
                    }
                    novelLog { "served from DB: \"${novel.title}\" chapters=${chapters.size} (no source hit)" }
                    val byOrder = chapters.sortedBy { it.sourceOrder }
                    mutableState.update { current ->
                        NovelDetailsState.Loaded(
                            novel = novel,
                            chapters = chapters,
                            isRefreshing = (current as? NovelDetailsState.Loaded)?.isRefreshing ?: false,
                            // Preserve transient screen state across DB re-emissions (a read/bookmark
                            // write re-emits the chapter list): an open dialog and the active selection.
                            dialog = (current as? NovelDetailsState.Loaded)?.dialog,
                            selection = (current as? NovelDetailsState.Loaded)?.selection ?: emptySet(),
                            // The FAB resumes the first unread chapter (or the last once all are read).
                            resumeChapter = byOrder.firstOrNull { !it.read } ?: byOrder.lastOrNull(),
                            hasStarted = chapters.any { it.read || it.lastTextProgress > 0 },
                        )
                    }
                    if (chapters.isEmpty()) maybeFirstFetch(novel)
                }
        }
    }

    /** Called by the screen after it resolves the plugin source (host construction needs a Context).
     *  Kicks off a pending first-open fetch if the stored chapter list is empty. */
    fun onSourceReady(resolved: NovelSource) {
        source = resolved
        val loaded = state.value as? NovelDetailsState.Loaded
        if (loaded == null || loaded.chapters.isEmpty()) maybeFirstFetch(loaded?.novel)
    }

    /** The source couldn't be resolved (uninstalled, etc.). Only fatal when there's no DB data to
     *  show; otherwise the cached list stays and refresh is simply unavailable. */
    fun onSourceFailed(message: String) {
        if (state.value !is NovelDetailsState.Loaded) {
            mutableState.value = NovelDetailsState.Failed(message)
        }
    }

    /** Force-fetch this one novel from the source (stale-then-fresh): the cached list stays visible
     *  under a spinner while the sync runs, then the Flow swaps in the fresh rows. Read/bookmark are
     *  preserved by [syncChaptersWithNovelSource]. Deduped against a concurrent refresh. */
    fun refresh() {
        val src = source ?: return
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        if (refreshJob?.isActive == true) return
        novelLog { "refresh: fetching from source" }
        refreshJob = screenModelScope.launchIO {
            mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = true) ?: it }
            try {
                runCatching { fetchAndSync(src, loaded.novel) }
            } finally {
                mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(isRefreshing = false) ?: it }
            }
        }
    }

    /** Fetch the chapter text for a stored chapter (always a source hit; reading isn't cached). */
    internal suspend fun loadChapterText(chapter: NovelChapter): ChapterRead {
        val src = source ?: error("Source not ready")
        return ChapterRead(
            chapterId = chapter.id ?: error("Stored chapter has no id"),
            initialProgress = chapter.lastTextProgress,
            paragraphs = htmlToParagraphs(src.parseChapter(chapter.url)),
        )
    }

    /** Absolute web URL for this novel. Prefer the plugin's `resolveUrl` (handles sources whose URL
     *  isn't a plain site+path); otherwise resolve the stored novel path against the site the same way
     *  a default resolveUrl would (`new URL(path, site)`), which reaches the novel page for nearly all
     *  sources since the path is the one parseNovel already used. Homepage only if that can't be built. */
    suspend fun novelWebUrl(): String? {
        val src = source ?: return null
        src.resolveUrl(novelUrl, isNovel = true)?.takeIf { it.isNotBlank() }?.let { return it }
        val site = src.site.takeIf { it.isNotBlank() } ?: return null
        if (novelUrl.isBlank()) return site
        return runCatching { URL(URL(site), novelUrl).toString() }.getOrNull() ?: site
    }

    private fun maybeFirstFetch(existing: Novel?) {
        if (firstFetchTried) return
        val src = source ?: return // defer until onSourceReady
        firstFetchTried = true
        novelLog { "first-open fetch (no stored chapters)" }
        screenModelScope.launchIO {
            runCatching { fetchAndSync(src, existing) }.onFailure { e ->
                if (state.value !is NovelDetailsState.Loaded) {
                    mutableState.value = NovelDetailsState.Failed(e.message ?: "Failed to load novel")
                }
            }
        }
    }

    /** parseNovel + persist + sync. For a library novel [existing] is the stored row; defensively
     *  inserts a favorited row if the novel somehow isn't persisted yet. The reactive Flow then
     *  re-emits the updated chapter list. */
    private suspend fun fetchAndSync(src: NovelSource, existing: Novel?) {
        val sourceNovel = src.parseNovel(novelUrl)
        val target = if (existing != null) {
            // Refresh stored metadata from the source. A novel added from a browse / search list
            // carries only name + path + cover (no summary), and syncChaptersWithNovelSource touches
            // only chapters, so the description would stay blank without this. Prefer freshly-parsed
            // values, but never wipe existing data with a null/blank from a partial re-parse.
            val parsed = sourceNovel.toNovel(sourceId = src.id, favorite = existing.favorite)
            // Blank-safe: some plugins return an empty summary/field on a partial parse (e.g. a
            // selector mismatch), so a null OR blank parsed value must keep the existing data
            // rather than wipe it.
            val merged = existing.copy(
                author = parsed.author?.takeIf { it.isNotBlank() } ?: existing.author,
                artist = parsed.artist?.takeIf { it.isNotBlank() } ?: existing.artist,
                description = parsed.description?.takeIf { it.isNotBlank() } ?: existing.description,
                genres = parsed.genres?.takeIf { it.isNotEmpty() } ?: existing.genres,
                status = if (parsed.status != NovelStatusCode.UNKNOWN) parsed.status else existing.status,
                thumbnailUrl = parsed.thumbnailUrl?.takeIf { it.isNotBlank() } ?: existing.thumbnailUrl,
            )
            if (merged != existing) novelRepo.update(merged)
            merged
        } else {
            val id = novelRepo.insert(sourceNovel.toNovel(sourceId = src.id, favorite = true)) ?: return
            novelRepo.getById(id) ?: return
        }
        val chapters = sourceNovel.chapters.orEmpty()
        if (chapters.isNotEmpty()) {
            syncChaptersWithNovelSource(chapters, target, src, chapterRepo, novelRepo, handler)
        }
    }

    // --- Favorite / categories ---

    /** Flip the library flag. Adding shows the category picker (the novel Flow re-emits favorite=true
     *  and rebuilds the heart; observeFromDb preserves the dialog across that re-emit). */
    fun toggleFavorite() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val novel = loaded.novel
            val id = novel.id ?: return@launchIO
            if (!novel.favorite) {
                novelRepo.update(novel.copy(favorite = true))
                val allCategories = getNovelCategories.await().filter { (it.id ?: 0) > 0 }
                if (allCategories.isNotEmpty()) {
                    val currentIds = getNovelCategories.awaitByNovelId(id)
                        .mapNotNull { it.id?.toLong() }
                        .toSet()
                    mutableState.update {
                        (it as? NovelDetailsState.Loaded)
                            ?.copy(dialog = NovelDetailsDialog.ChangeCategory(allCategories, currentIds)) ?: it
                    }
                }
            } else {
                novelRepo.update(novel.copy(favorite = false))
            }
        }
    }

    /** Show the categories picker for a novel already in the library. */
    fun showChangeCategoryDialog() {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val id = loaded.novel.id ?: return@launchIO
            val allCategories = getNovelCategories.await().filter { (it.id ?: 0) > 0 }
            if (allCategories.isEmpty()) return@launchIO
            val currentIds = getNovelCategories.awaitByNovelId(id)
                .mapNotNull { it.id?.toLong() }
                .toSet()
            mutableState.update {
                (it as? NovelDetailsState.Loaded)
                    ?.copy(dialog = NovelDetailsDialog.ChangeCategory(allCategories, currentIds)) ?: it
            }
        }
    }

    fun applyCategories(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            setNovelCategories.await(loaded.novel.id, categoryIds)
            dismissDialog()
        }
    }

    fun dismissDialog() {
        mutableState.update { (it as? NovelDetailsState.Loaded)?.copy(dialog = null) ?: it }
    }

    // --- Chapter selection + read/bookmark ---

    fun toggleSelection(chapterId: Long, selected: Boolean, fromLongPress: Boolean) {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        val chapters = loaded.chapters
        val index = chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) return
        if ((chapterId in loaded.selection) == selected) return

        val newSelection = loaded.selection.toMutableSet()
        val firstSelection = loaded.selection.isEmpty()
        if (selected) newSelection.add(chapterId) else newSelection.remove(chapterId)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = index
                selectedPositions[1] = index
            } else {
                val range = when {
                    index < selectedPositions[0] -> (index + 1 until selectedPositions[0]).also { selectedPositions[0] = index }
                    index > selectedPositions[1] -> (selectedPositions[1] + 1 until index).also { selectedPositions[1] = index }
                    else -> IntRange.EMPTY
                }
                range.forEach { i -> chapters[i].id?.let { newSelection.add(it) } }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (index == selectedPositions[0]) selectedPositions[0] = chapters.indexOfFirst { it.id in newSelection }
                else if (index == selectedPositions[1]) selectedPositions[1] = chapters.indexOfLast { it.id in newSelection }
            } else {
                if (index < selectedPositions[0]) selectedPositions[0] = index
                else if (index > selectedPositions[1]) selectedPositions[1] = index
            }
        }
        mutableState.value = loaded.copy(selection = newSelection)
    }

    fun selectAll() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        resetSelectionAnchors()
        mutableState.value = loaded.copy(selection = loaded.chapters.mapNotNull { it.id }.toSet())
    }

    fun invertSelection() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        resetSelectionAnchors()
        val inverted = loaded.chapters.mapNotNull { it.id }.filterNot { it in loaded.selection }.toSet()
        mutableState.value = loaded.copy(selection = inverted)
    }

    fun clearSelection() {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return
        resetSelectionAnchors()
        if (loaded.selection.isNotEmpty()) mutableState.value = loaded.copy(selection = emptySet())
    }

    private fun resetSelectionAnchors() {
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun markSelectedRead(read: Boolean) {
        screenModelScope.launchIO {
            applyRead(selectedChapters() ?: return@launchIO, read)
            clearSelection()
        }
    }

    fun bookmarkSelected(bookmark: Boolean) {
        screenModelScope.launchIO {
            applyBookmark(selectedChapters() ?: return@launchIO, bookmark)
            clearSelection()
        }
    }

    fun markAllRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            applyRead(loaded.chapters, read)
        }
    }

    /** Mark every chapter before the earliest selected one (in source order) read/unread. */
    fun markPreviousRead(read: Boolean) {
        screenModelScope.launchIO {
            val loaded = state.value as? NovelDetailsState.Loaded ?: return@launchIO
            val ascending = loaded.chapters.sortedBy { it.sourceOrder }
            val earliest = ascending.indexOfFirst { it.id in loaded.selection }
            if (earliest > 0) applyRead(ascending.subList(0, earliest), read)
            clearSelection()
        }
    }

    private fun selectedChapters(): List<NovelChapter>? {
        val loaded = state.value as? NovelDetailsState.Loaded ?: return null
        return loaded.chapters.filter { it.id in loaded.selection }
    }

    private suspend fun applyRead(targets: List<NovelChapter>, read: Boolean) {
        targets.forEach { if (it.read != read) chapterRepo.update(it.copy(read = read)) }
    }

    private suspend fun applyBookmark(targets: List<NovelChapter>, bookmark: Boolean) {
        targets.forEach { if (it.bookmark != bookmark) chapterRepo.update(it.copy(bookmark = bookmark)) }
    }

    /** Temporary verification aid (Phase 7), filterable via tag:NovelDetailsPort; stripped in P9. */
    private fun novelLog(message: () -> String) {
        if (BuildConfig.DEBUG) Logger.withTag("NovelDetailsPort").d(message())
    }
}

sealed interface NovelDetailsState {
    data object Loading : NovelDetailsState
    data class Loaded(
        val novel: Novel,
        val chapters: List<NovelChapter>,
        val isRefreshing: Boolean,
        val dialog: NovelDetailsDialog? = null,
        val selection: Set<Long> = emptySet(),
        val resumeChapter: NovelChapter? = null,
        val hasStarted: Boolean = false,
    ) : NovelDetailsState
    data class Failed(val message: String) : NovelDetailsState
}

sealed interface NovelDetailsDialog {
    data class ChangeCategory(
        val allCategories: List<NovelCategory>,
        val currentCategoryIds: Set<Long>,
    ) : NovelDetailsDialog
}
