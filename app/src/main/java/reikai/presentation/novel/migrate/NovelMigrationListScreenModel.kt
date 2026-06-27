package reikai.presentation.novel.migrate

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.data.novel.refreshNovelFromSource
import reikai.data.novel.toNovel
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.MigrateNovelUseCase
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.hasCustomCover
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreenModel
import reikai.presentation.novel.globalsearch.SearchState
import reikai.presentation.novel.globalsearch.SourceSearchResult
import reikai.presentation.novel.globalsearch.selectGlobalSearchSources
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.data.Database
import uy.kohesive.injekt.injectLazy

/** Max sources searched concurrently, SHARED across every row, so an on-scroll fan-out across many
 *  rows can never exceed this many in-flight host hits (the deliberate difference from the standalone
 *  global search, which throttles within a single query). */
private const val SEARCH_CONCURRENCY = 5

/**
 * Backs the unified novel migration screen for 1..N novels. Each row auto-searches its title across
 * sources the first time it scrolls into view (search-on-scroll, paced by [searchSemaphore]); the user
 * accepts the suggested top hit or overrides it, the chosen target is materialised lazily, then the
 * whole batch is Copied / Migrated through [MigrateNovelUseCase]. The single-novel Migrate is just this
 * screen with one row.
 */
class NovelMigrationListScreenModel(
    private val novelIds: List<Long>,
) : StateScreenModel<NovelMigrationListScreenModel.State>(State()) {

    private val novelRepository: NovelRepository by injectLazy()
    private val chapterRepository: NovelChapterRepository by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val database: Database by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val sourcePreferences: ReikaiSourcePreferences by injectLazy()
    private val novelPreferences: NovelPreferences by injectLazy()
    private val migrateNovel: MigrateNovelUseCase by injectLazy()
    private val coverCache: CoverCache by injectLazy()

    private val searchSemaphore = Semaphore(SEARCH_CONCURRENCY)

    init {
        val savedFlags = NovelMigrationFlag.fromBits(novelPreferences.novelMigrationFlags().get())
        mutableState.update { it.copy(initialFlags = savedFlags) }
        screenModelScope.launchIO {
            try { installer.ensureLoaded() } catch (_: Throwable) {}
            val rows = novelIds.mapNotNull { id ->
                novelRepository.getById(id)?.let { novel ->
                    Row(novel = novel, sourceChapterCount = chapterRepository.getByNovelId(novel.id).size)
                }
            }
            mutableState.update { it.copy(rows = rows) }
        }
    }

    /** Auto-search a row the first time it becomes visible (idempotent). */
    fun searchRow(novelId: Long) {
        val row = state.value.rows.firstOrNull { it.novel.id == novelId } ?: return
        if (row.searchStarted) return
        setRow(novelId) { it.copy(searchStarted = true) }
        runSearch(novelId, row.novel.title, row.novel.source)
    }

    /** Re-run a row's search with a user-edited query (the override path). */
    fun research(novelId: Long, query: String) {
        val row = state.value.rows.firstOrNull { it.novel.id == novelId } ?: return
        runSearch(novelId, query, row.novel.source)
    }

    private fun runSearch(novelId: Long, query: String, currentSource: String) {
        // All installed sources (migration should find a target anywhere), minus the novel's own source.
        val sources = selectGlobalSearchSources(
            sourceManager.getAll(),
            sourcePreferences.pinnedNovelSources.get(),
            NovelGlobalSearchScreenModel.SourceFilter.All,
        ).filter { it.id != currentSource }
        setRow(novelId) { it.copy(results = sources.map { s -> SourceSearchResult(s, SearchState.Loading) }) }
        if (query.isBlank()) return
        sources.forEach { source ->
            screenModelScope.launchIO {
                val result = searchSemaphore.withPermit {
                    try {
                        SearchState.Success(source.searchNovels(query, 1))
                    } catch (e: Throwable) {
                        SearchState.Error("${e.javaClass.simpleName}: ${e.message ?: ""}")
                    }
                }
                setRow(novelId) { row ->
                    row.copy(results = row.results.map { if (it.source.id == source.id) it.copy(state = result) else it })
                }
            }
        }
    }

    /** Accept a row's suggested hit (first non-empty source result). */
    fun acceptSuggested(novelId: Long) {
        val suggestion = state.value.rows.firstOrNull { it.novel.id == novelId }?.suggested ?: return
        pick(novelId, suggestion.first.id, suggestion.second.path)
    }

    /** Pick a specific result for a row and materialise it as that row's target (lazy fetch happens here). */
    fun pick(novelId: Long, sourceId: String, url: String) {
        setRow(novelId) { it.copy(resolving = true) }
        screenModelScope.launchIO {
            val target = runCatching { materialize(sourceId, url) }.getOrNull()
            // Target chapters were just fetched by materialize, so this count is a free local read.
            val targetCount = target?.let { chapterRepository.getByNovelId(it.id).size }
            val site = sourceManager.get(sourceId)?.site
            setRow(novelId) {
                it.copy(
                    resolving = false,
                    chosenTarget = target,
                    chosenSite = site,
                    targetChapterCount = targetCount,
                    expanded = false,
                )
            }
        }
    }

    fun clearChoice(novelId: Long) = setRow(novelId) {
        it.copy(chosenTarget = null, chosenSite = null, targetChapterCount = null)
    }

    fun toggleExpanded(novelId: Long) = setRow(novelId) { it.copy(expanded = !it.expanded) }

    /** Show the confirm dialog, computing which flags are worth offering across the chosen rows: cover
     *  / notes only if at least one source novel actually has one (the novel twin of manga's
     *  applicable-flags). Chapter / category always apply. */
    fun showConfirm() {
        val chosen = state.value.rows.mapNotNull { row -> row.novel.takeIf { row.chosenTarget != null } }
        val applicable = NovelMigrationFlag.ALL.filterTo(LinkedHashSet()) { flag ->
            when (flag) {
                NovelMigrationFlag.COVER -> chosen.any { it.hasCustomCover(coverCache) }
                NovelMigrationFlag.NOTES -> chosen.any { it.notes.isNotBlank() }
                else -> true
            }
        }
        mutableState.update { it.copy(showConfirm = true, applicableFlags = applicable) }
    }

    fun dismissConfirm() = mutableState.update { it.copy(showConfirm = false) }

    /** Copy / Migrate every row that has a chosen target, then signal the screen to leave. */
    fun migrate(flags: Set<NovelMigrationFlag>, replace: Boolean) {
        novelPreferences.novelMigrationFlags().set(NovelMigrationFlag.toBits(flags))
        mutableState.update { it.copy(showConfirm = false, isMigrating = true) }
        screenModelScope.launchIO {
            state.value.rows.forEach { row ->
                val target = row.chosenTarget ?: return@forEach
                runCatching { migrateNovel(row.novel, target, flags, replace) }
            }
            mutableState.update { it.copy(isMigrating = false, migrated = true) }
        }
    }

    private suspend fun materialize(sourceId: String, url: String): Novel? {
        val source = sourceManager.get(sourceId) ?: return null
        val sourceNovel = source.parseNovel(url)
        novelRepository.insertOrGet(sourceNovel.toNovel(sourceId = source.id, favorite = false)) ?: return null
        val stored = novelRepository.getByUrlAndSource(url, source.id) ?: return null
        refreshNovelFromSource(stored, source, chapterRepository, novelRepository, database)
        return novelRepository.getByUrlAndSource(url, source.id)
    }

    private inline fun setRow(novelId: Long, crossinline transform: (Row) -> Row) {
        mutableState.update { st ->
            st.copy(rows = st.rows.map { if (it.novel.id == novelId) transform(it) else it })
        }
    }

    data class State(
        val rows: List<Row> = emptyList(),
        val isMigrating: Boolean = false,
        val migrated: Boolean = false,
        val showConfirm: Boolean = false,
        val initialFlags: Set<NovelMigrationFlag> = NovelMigrationFlag.ALL,
        val applicableFlags: Set<NovelMigrationFlag> = NovelMigrationFlag.ALL,
    ) {
        val chosenCount: Int get() = rows.count { it.chosenTarget != null }
        val skippedCount: Int get() = rows.size - chosenCount
    }

    data class Row(
        val novel: Novel,
        val searchStarted: Boolean = false,
        val results: List<SourceSearchResult> = emptyList(),
        val resolving: Boolean = false,
        val chosenTarget: Novel? = null,
        /** The chosen target's source site, for the target cover's Referer. */
        val chosenSite: String? = null,
        /** The source novel's chapter count (free local read; shown always). */
        val sourceChapterCount: Int = 0,
        /** The chosen target's chapter count, read after [materialize] populates it; null until chosen. */
        val targetChapterCount: Int? = null,
        val expanded: Boolean = false,
    ) {
        /** The suggested target: the first non-empty source result (sources are pinned-first, then name). */
        val suggested: Pair<NovelSource, NovelItem>?
            get() = results.firstNotNullOfOrNull { r ->
                (r.state as? SearchState.Success)?.novels?.firstOrNull()?.let { r.source to it }
            }

        /** Flattened candidate list for the override view: every source's results, in source order. */
        val candidates: List<Pair<NovelSource, NovelItem>>
            get() = results.flatMap { r ->
                (r.state as? SearchState.Success)?.novels?.map { r.source to it }.orEmpty()
            }

        val searching: Boolean get() = results.any { it.state is SearchState.Loading }
    }
}
