package yokai.presentation.novel.details

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig
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
import yokai.data.novel.toNovel
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
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

    /** Resolved by the screen once the plugin host loads it; null until then. Source-dependent ops
     *  (first-open fetch, refresh, chapter text) no-op or defer until it's set. */
    @Volatile
    private var source: NovelSource? = null

    /** A library novel already has its chapters synced at favorite time, so the first-open fetch is
     *  a rare fallback; guard it so it runs at most once. */
    private var firstFetchTried = false
    private var refreshJob: Job? = null

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
                    mutableState.update { current ->
                        NovelDetailsState.Loaded(
                            novel = novel,
                            chapters = chapters,
                            isRefreshing = (current as? NovelDetailsState.Loaded)?.isRefreshing ?: false,
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
        val target = existing ?: run {
            val id = novelRepo.insert(sourceNovel.toNovel(sourceId = src.id, favorite = true)) ?: return
            novelRepo.getById(id) ?: return
        }
        val chapters = sourceNovel.chapters.orEmpty()
        if (chapters.isNotEmpty()) {
            syncChaptersWithNovelSource(chapters, target, src, chapterRepo, novelRepo, handler)
        }
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
    ) : NovelDetailsState
    data class Failed(val message: String) : NovelDetailsState
}
