package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/** Max sources searched concurrently, shared across every row, so the whole-batch fan-out can never
 *  exceed this many in-flight source hits. */
private const val SEARCH_CONCURRENCY = 5

/**
 * The unified migration list for 1..N entries of one content type, over the [MigrationFlowAdapter]
 * seam. Every row auto-searches eagerly at load (bounded by the shared semaphore), so accept-all and
 * the commit gate cover the full batch, not just the rows scrolled into view; the user accepts the
 * suggested match or overrides it inline; the batch commits through the adapter with per-row failure
 * capture and retry. Behavior and UI contract: the design note in
 * docs/dev/plans/content-layer-migrate-surface.md.
 */
class EntryMigrationListScreenModel(
    contentType: ContentType,
    private val entryIds: List<Long>,
    private val extraQuery: String?,
) : StateScreenModel<EntryMigrationListScreenModel.State>(State()) {

    private val adapter: MigrationFlowAdapter = when (contentType) {
        ContentType.MANGA -> Injekt.get<MangaMigrationFlowAdapter>()
        else -> Injekt.get<NovelMigrationFlowAdapter>()
    }

    private val searchSemaphore = Semaphore(SEARCH_CONCURRENCY)

    /** Bumped whenever tuning changes so an in-flight search from the old tuning drops its write. */
    private var searchGeneration = 0

    /** One search job per row, so [applyTuning] can cancel the superseded wave instead of letting it
     *  hold the semaphore permits (which starved the re-search behind dead network hops). */
    private val rowJobs = ConcurrentHashMap<EntryId, Job>()
    private var commitJob: Job? = null

    private val _events = Channel<Event>()
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val tuning = adapter.readTuning().copy(extraQuery = extraQuery)
            val entries = adapter.loadEntries(entryIds)
            mutableState.update { st ->
                st.copy(
                    isLoading = false,
                    tuning = tuning,
                    supportsSmartMatch = adapter.supportsSmartMatch,
                    supportsChapterComparison = adapter.suggestsChapterCounts,
                    initialFlags = adapter.savedFlags(),
                    rows = entries.map { Row(entry = it) },
                )
            }
            startSearches()
        }
    }

    /** The ordered target sources for one row: the saved config selection resolved against the
     *  enabled set (a since-disabled saved source drops out); with nothing saved, pinned sources
     *  lead. The row's own source stays searchable; the adapters reject its identical listing. */
    private fun sourcesFor(row: Row): List<MigrationSourceUi> {
        val enabled = adapter.enabledSources()
        val saved = adapter.savedSelection()
        return if (saved.isEmpty()) {
            val pinned = adapter.pinnedKeys()
            enabled.sortedBy { it.key !in pinned }
        } else {
            val byKey = enabled.associateBy { it.key }
            saved.mapNotNull { byKey[it] }
        }
    }

    /** Kick off every not-yet-started row search; each row runs its own job under the semaphore. */
    private fun startSearches() {
        state.value.rows.filter { !it.searchStarted }.forEach { searchRow(it.entry.id) }
    }

    /** Search one row (idempotent; re-armed by [applyTuning]). */
    private fun searchRow(id: EntryId) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        if (row.searchStarted) return
        setRow(id) { it.copy(searchStarted = true, searching = true) }
        val generation = searchGeneration
        rowJobs[id] = screenModelScope.launchIO {
            val tuning = state.value.tuning
            val sources = sourcesFor(row)
            val suggestion = if (tuning.prioritizeByChapters && adapter.supportsSmartMatch) {
                // Fan every source out in parallel (bounded by the shared semaphore, matching
                // upstream) and keep the target furthest ahead: by latest chapter number when known
                // (sources split/bundle chapters differently, so row count lies), else by count.
                // A zero-chapter hit is never suggested, matching upstream.
                coroutineScope {
                    sources.map { source ->
                        async {
                            searchSemaphore.withPermit {
                                runCatchingCancellable { adapter.suggest(row.entry, source.key, tuning) }.getOrNull()
                            }?.takeIf { (it.chapterCount ?: 0) > 0 }?.let { source to it }
                        }
                    }.awaitAll()
                }.filterNotNull().maxByOrNull { (_, candidate) ->
                    candidate.latestChapter ?: candidate.chapterCount?.toDouble() ?: -1.0
                }
            } else {
                // Priority order: the first selected source with a hit is the suggestion.
                sources.firstNotNullOfOrNull { source ->
                    searchSemaphore.withPermit {
                        runCatchingCancellable { adapter.suggest(row.entry, source.key, tuning) }.getOrNull()
                    }?.let { source to it }
                }
            }
            if (generation != searchGeneration) return@launchIO
            setRow(id) {
                it.copy(
                    searching = false,
                    suggested = suggestion?.second,
                    suggestedSourceName = suggestion?.first?.name,
                )
            }
        }
    }

    /** Re-run a row's search with a user-edited query, filling the override strips. Sources run in
     *  parallel under the shared semaphore; a failed source keeps its strip with the error text
     *  instead of disappearing into "no results". */
    fun research(id: EntryId, query: String) {
        if (query.isBlank()) return
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        setRow(id) { it.copy(overrideLoading = true) }
        screenModelScope.launchIO {
            val strips = coroutineScope {
                sourcesFor(row).map { source ->
                    async {
                        searchSemaphore.withPermit {
                            runCatchingCancellable { adapter.candidates(row.entry, query, source.key) }
                        }.fold(
                            onSuccess = {
                                OverrideStrip(sourceKey = source.key, sourceName = source.name, candidates = it)
                            },
                            onFailure = {
                                OverrideStrip(
                                    sourceKey = source.key,
                                    sourceName = source.name,
                                    candidates = emptyList(),
                                    error = it.message ?: it.javaClass.simpleName,
                                )
                            },
                        )
                    }
                }.awaitAll()
            }
            // Every searched source keeps its strip, even empty: the strip header carries the deep
            // browse affordance, which matters most when the inline search found nothing.
            setRow(id) { it.copy(overrideLoading = false, overrideStrips = strips) }
        }
    }

    /** Accept the suggestion (or un-accept a chosen target back to suggested). */
    fun toggleAccept(id: EntryId) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        if (row.chosen != null) {
            setRow(id) { it.copy(chosen = null, chosenSourceName = null) }
            return
        }
        val suggested = row.suggested ?: return
        pick(id, suggested, row.suggestedSourceName)
    }

    /** Pick a candidate (suggested or from an override strip) and resolve it into the row's target. */
    fun pick(id: EntryId, candidate: MigrationCandidate, sourceName: String?) {
        setRow(id) { it.copy(resolving = true) }
        screenModelScope.launchIO {
            val resolved = runCatchingCancellable { adapter.resolve(candidate) }.getOrNull()
            if (resolved == null) _events.send(Event.PickFailed)
            setRow(id) {
                it.copy(
                    resolving = false,
                    chosen = resolved,
                    chosenSourceName = sourceName.takeIf { _ -> resolved != null },
                    expanded = false,
                    skipped = if (resolved != null) false else it.skipped,
                )
            }
        }
    }

    /** Accept every visible suggested match with no chosen target yet; resolution happens at commit,
     *  so a large list accepts instantly. Visible only: a row the hide toggles filtered out must not
     *  be accepted behind the user's back (accepting would pop it back into view). */
    fun acceptAllSuggestions() {
        val visible = state.value.visibleRows.mapTo(HashSet()) { it.entry.id }
        mutableState.update { st ->
            st.copy(
                rows = st.rows.map {
                    if (it.entry.id in visible && it.chosen == null && !it.skipped && it.suggested != null) {
                        it.copy(chosen = it.suggested, chosenSourceName = it.suggestedSourceName)
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Skip keeps the chosen target so unskip restores the row exactly (the design's "restorable"). */
    fun toggleSkip(id: EntryId) = setRow(id) { it.copy(skipped = !it.skipped) }

    fun toggleExpanded(id: EntryId) = setRow(id) { it.copy(expanded = !it.expanded) }

    /** Persist edited tuning and re-run every row's search under it. The superseded wave is cancelled
     *  (it would otherwise hold the semaphore permits through dead network hops) and the generation
     *  bump drops any write already past its cancellation point. */
    fun applyTuning(tuning: MigrationTuning) {
        adapter.persistTuning(tuning)
        searchGeneration++
        rowJobs.values.forEach { it.cancel() }
        rowJobs.clear()
        mutableState.update { st ->
            st.copy(
                tuning = tuning,
                rows = st.rows.map {
                    it.copy(searchStarted = false, searching = false, suggested = null, suggestedSourceName = null)
                },
            )
        }
        startSearches()
    }

    fun showConfirm(replace: Boolean) {
        val chosenRows = state.value.rows.filter { it.chosen != null && !it.skipped }
        screenModelScope.launchIO {
            val applicable = adapter.applicableFlags(chosenRows.map { it.entry })
            // Re-read the saved set: a commit earlier in this session rewrote the pref, and the
            // one-shot init snapshot would reseed the second confirm with stale checks.
            val saved = adapter.savedFlags()
            mutableState.update {
                it.copy(
                    showConfirm = true,
                    confirmReplace = replace,
                    applicableFlags = applicable,
                    initialFlags = saved,
                )
            }
        }
    }

    fun dismissConfirm() = mutableState.update { it.copy(showConfirm = false) }

    /** Commit every chosen row; a failed row stays in place with an error and a retry. An
     *  accept-all row carries an unresolved suggestion, so each row resolves here first (a no-op
     *  when already resolved); the resolved target is written back so retry commits it, not the
     *  unresolved suggestion (which the novel adapter rejects). */
    fun commit(flags: Set<MigrationDataFlag>, replace: Boolean) {
        val targets = state.value.rows.filter { it.chosen != null && !it.skipped && !it.migratedOk }
        mutableState.update {
            it.copy(
                showConfirm = false,
                isMigrating = true,
                progressDone = 0,
                progressTotal = targets.size,
                lastFlags = flags,
                lastReplace = replace,
            )
        }
        commitJob = screenModelScope.launchIO {
            var failures = 0
            var succeeded = 0
            try {
                targets.forEach { row ->
                    if (state.value.cancelRequested) return@forEach
                    var resolved: MigrationCandidate? = null
                    val failed = try {
                        resolved = adapter.resolve(row.chosen!!) ?: error("target failed to resolve")
                        adapter.migrate(row.entry, resolved, replace, flags)
                        false
                    } catch (e: CancellationException) {
                        // The engines are not transactional: a cancelled row may be half-applied.
                        // Mark it failed so it resurfaces with a retry instead of looking pristine
                        // (a retry re-runs the whole sequence, which is safe, as a plain re-commit was).
                        setRow(row.entry.id) { it.copy(chosen = resolved ?: it.chosen, failed = true) }
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "Migration failed for ${row.entry.id}" }
                        true
                    }
                    if (failed) failures++ else succeeded++
                    setRow(row.entry.id) {
                        it.copy(chosen = resolved ?: it.chosen, migratedOk = !failed, failed = failed)
                    }
                    mutableState.update { it.copy(progressDone = it.progressDone + 1) }
                }
            } finally {
                mutableState.update {
                    it.copy(
                        isMigrating = false,
                        finishedCount = it.finishedCount + succeeded,
                        finished = failures == 0 && !it.cancelRequested && it.progressDone == it.progressTotal,
                        cancelRequested = false,
                    )
                }
            }
        }
    }

    /** Cancels the in-flight row too (the engines rethrow cancellation), not just the queue; the
     *  interrupted row is marked failed by [commit] so it stays visible and retryable. */
    fun cancelCommit() {
        mutableState.update { it.copy(cancelRequested = true) }
        commitJob?.cancel()
    }

    /** Commit one row immediately with the saved flags (the per-row "Migrate now" / "Copy now"). */
    fun commitSingle(id: EntryId, replace: Boolean) {
        if (state.value.isMigrating) return
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        val target = row.chosen ?: row.suggested ?: return
        setRow(id) { it.copy(resolving = true, failed = false) }
        screenModelScope.launchIO {
            val result = runCatchingCancellable {
                val resolved = adapter.resolve(target) ?: error("target failed to resolve")
                adapter.migrate(row.entry, resolved, replace, adapter.savedFlags())
                resolved
            }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Single-row migration failed for ${row.entry.id}" } }
            setRow(id) {
                it.copy(
                    resolving = false,
                    chosen = result.getOrNull() ?: target,
                    chosenSourceName = it.chosenSourceName ?: it.suggestedSourceName,
                    migratedOk = result.isSuccess,
                    failed = result.isFailure,
                )
            }
        }
    }

    /** Retry one failed row with the last commit's flags and verb (or the saved flags when the
     *  failure came from a single-row commit). Resolves first: the stored chosen may still be the
     *  unresolved suggestion when the original resolve was the step that failed. */
    fun retryRow(id: EntryId) {
        val st = state.value
        val row = st.rows.firstOrNull { it.entry.id == id } ?: return
        val target = row.chosen ?: return
        val flags = st.lastFlags ?: adapter.savedFlags()
        setRow(id) { it.copy(failed = false, resolving = true) }
        screenModelScope.launchIO {
            val result = runCatchingCancellable {
                val resolved = adapter.resolve(target) ?: error("target failed to resolve")
                adapter.migrate(row.entry, resolved, st.lastReplace, flags)
                resolved
            }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Migration retry failed for ${row.entry.id}" } }
            setRow(id) {
                it.copy(
                    resolving = false,
                    chosen = result.getOrNull() ?: target,
                    migratedOk = result.isSuccess,
                    failed = result.isFailure,
                )
            }
            if (result.isSuccess) {
                mutableState.update { it.copy(finishedCount = it.finishedCount + 1) }
                // Only a batch commit's retry finishes the screen; a single-row retry leaves the
                // user in the list to keep working.
                if (st.lastFlags != null && state.value.rows.none { it.failed }) {
                    mutableState.update { it.copy(finished = true) }
                }
            }
        }
    }

    private inline fun setRow(id: EntryId, crossinline transform: (Row) -> Row) {
        mutableState.update { st ->
            st.copy(rows = st.rows.map { if (it.entry.id == id) transform(it) else it })
        }
    }

    /** Apply a deep-picker hand-back: both rows are already stored, so the target wraps into a
     *  resolved candidate without a search. */
    fun overrideWithStored(currentRawId: Long, targetRawId: Long) {
        val row = state.value.rows.firstOrNull { it.entry.id.rawId == currentRawId } ?: return
        screenModelScope.launchIO {
            val candidate = runCatchingCancellable { adapter.storedCandidate(targetRawId) }.getOrNull()
            if (candidate == null) {
                _events.send(Event.PickFailed)
                return@launchIO
            }
            setRow(row.entry.id) {
                it.copy(
                    chosen = candidate,
                    chosenSourceName = adapter.sourceDisplayName(candidate.sourceKey),
                    expanded = false,
                    skipped = false,
                )
            }
        }
    }

    enum class Event { PickFailed }

    data class OverrideStrip(
        val sourceKey: String,
        val sourceName: String,
        val candidates: List<MigrationCandidate>,
        val error: String? = null,
    )

    data class Row(
        val entry: MigrationEntry,
        val searchStarted: Boolean = false,
        val searching: Boolean = false,
        val suggested: MigrationCandidate? = null,
        val suggestedSourceName: String? = null,
        val overrideStrips: List<OverrideStrip> = emptyList(),
        val overrideLoading: Boolean = false,
        val resolving: Boolean = false,
        val chosen: MigrationCandidate? = null,
        val chosenSourceName: String? = null,
        val skipped: Boolean = false,
        val expanded: Boolean = false,
        val migratedOk: Boolean = false,
        val failed: Boolean = false,
    )

    data class State(
        val isLoading: Boolean = true,
        val tuning: MigrationTuning = MigrationTuning(),
        val supportsSmartMatch: Boolean = false,
        val supportsChapterComparison: Boolean = false,
        val rows: List<Row> = emptyList(),
        val isMigrating: Boolean = false,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val cancelRequested: Boolean = false,
        val finished: Boolean = false,
        val finishedCount: Int = 0,
        val showConfirm: Boolean = false,
        val confirmReplace: Boolean = true,
        val initialFlags: Set<MigrationDataFlag> = MigrationDataFlag.entries.toSet(),
        val applicableFlags: Set<MigrationDataFlag> = MigrationDataFlag.entries.toSet(),
        val lastFlags: Set<MigrationDataFlag>? = null,
        val lastReplace: Boolean = true,
    ) {
        /** Rows after the hide toggles; the scroll list renders these. A row with a chosen target is
         *  never hidden, so an accepted match cannot be committed invisibly. */
        val visibleRows: List<Row>
            get() = rows.filter { row ->
                if (row.chosen != null) return@filter true
                if (tuning.hideUnmatched && row.searchStarted && !row.searching && row.suggested == null) {
                    return@filter false
                }
                if (tuning.hideWithoutUpdates) {
                    // Latest chapter number when both sides know it (row counts lie across sources
                    // that split/bundle chapters differently, matching upstream); counts otherwise.
                    val targetLatest = row.suggested?.latestChapter
                    val currentLatest = row.entry.latestChapter
                    if (targetLatest != null && currentLatest != null) {
                        if (targetLatest <= currentLatest) return@filter false
                    } else {
                        val targetCount = row.suggested?.chapterCount
                        val currentCount = row.entry.chapterCount
                        if (targetCount != null && currentCount != null && targetCount <= currentCount) {
                            return@filter false
                        }
                    }
                }
                true
            }
        val chosenCount: Int get() = rows.count { it.chosen != null && !it.skipped && !it.migratedOk }

        /** What the confirm dialog reports as left untouched: skipped or target-less rows that have
         *  not already migrated (an already-migrated row is neither skipped nor pending). */
        val skippedCount: Int get() = rows.count { !it.migratedOk && (it.skipped || it.chosen == null) }
        val hasUnacceptedSuggestions: Boolean
            get() = rows.any { it.chosen == null && !it.skipped && it.suggested != null }
        val searchedCount: Int get() = rows.count { it.searchStarted && !it.searching }
        val allSearched: Boolean get() = rows.isNotEmpty() && rows.all { it.searchStarted && !it.searching }
        val hasFailures: Boolean get() = rows.any { it.failed }
    }
}
