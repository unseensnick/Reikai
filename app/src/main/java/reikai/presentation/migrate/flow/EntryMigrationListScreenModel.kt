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

/** Max sources searched concurrently, shared across every row (the novel list's proven model), so a
 *  scroll-triggered fan-out can never exceed this many in-flight source hits. */
private const val SEARCH_CONCURRENCY = 5

/**
 * The unified migration list for 1..N entries of one content type, over the [MigrationFlowAdapter]
 * seam. Each row auto-searches when it first composes (search-on-scroll); the user accepts the
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
                    initialFlags = adapter.savedFlags(),
                    rows = entries.map { Row(entry = it) },
                )
            }
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

    /** Auto-search a row the first time it composes (idempotent; re-armed by [applyTuning]). */
    fun searchRow(id: EntryId) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        if (row.searchStarted) return
        setRow(id) { it.copy(searchStarted = true, searching = true) }
        val generation = searchGeneration
        screenModelScope.launchIO {
            val tuning = state.value.tuning
            val sources = sourcesFor(row)
            val suggestion = if (tuning.prioritizeByChapters && adapter.supportsSmartMatch) {
                // Fan every source out and keep the target with the most chapters (the adapter fills
                // counts at suggest time); a zero-chapter hit is never suggested, matching upstream.
                sources.mapNotNull { source ->
                    searchSemaphore.withPermit {
                        runCatching { adapter.suggest(row.entry, source.key, tuning) }.getOrNull()
                    }?.takeIf { (it.chapterCount ?: 0) > 0 }?.let { source to it }
                }.maxByOrNull { (_, candidate) -> candidate.chapterCount ?: -1 }
            } else {
                // Priority order: the first selected source with a hit is the suggestion.
                sources.firstNotNullOfOrNull { source ->
                    searchSemaphore.withPermit {
                        runCatching { adapter.suggest(row.entry, source.key, tuning) }.getOrNull()
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
                            runCatching { adapter.candidates(row.entry, query, source.key) }
                        }.fold(
                            onSuccess = {
                                OverrideStrip(sourceKey = source.key, sourceName = source.name, candidates = it)
                            },
                            onFailure = {
                                OverrideStrip(
                                    sourceKey = source.key,
                                    sourceName = source.name,
                                    candidates = emptyList(),
                                    error = "${it.javaClass.simpleName}: ${it.message.orEmpty()}",
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
            val resolved = runCatching { adapter.resolve(candidate) }.getOrNull()
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

    /** Accept every suggested match with no chosen target yet; resolution happens at commit, so a
     *  large list accepts instantly. */
    fun acceptAllSuggestions() {
        mutableState.update { st ->
            st.copy(
                rows = st.rows.map {
                    if (it.chosen == null && !it.skipped && it.suggested != null) {
                        it.copy(chosen = it.suggested, chosenSourceName = it.suggestedSourceName)
                    } else {
                        it
                    }
                },
            )
        }
    }

    fun toggleSkip(id: EntryId) = setRow(id) {
        it.copy(skipped = !it.skipped, chosen = null, chosenSourceName = null)
    }

    fun toggleExpanded(id: EntryId) = setRow(id) { it.copy(expanded = !it.expanded) }

    /** Persist edited tuning and re-run every row's search under it. The generation bump makes any
     *  in-flight old-tuning search drop its write instead of landing a stale suggestion. */
    fun applyTuning(tuning: MigrationTuning) {
        adapter.persistTuning(tuning)
        searchGeneration++
        mutableState.update { st ->
            st.copy(
                tuning = tuning,
                rows = st.rows.map {
                    it.copy(searchStarted = false, searching = false, suggested = null, suggestedSourceName = null)
                },
            )
        }
    }

    fun showConfirm(replace: Boolean) {
        val chosenRows = state.value.rows.filter { it.chosen != null && !it.skipped }
        screenModelScope.launchIO {
            val applicable = adapter.applicableFlags(chosenRows.map { it.entry })
            mutableState.update {
                it.copy(showConfirm = true, confirmReplace = replace, applicableFlags = applicable)
            }
        }
    }

    fun dismissConfirm() = mutableState.update { it.copy(showConfirm = false) }

    /** Commit every chosen row; a failed row stays in place with an error and a retry. An
     *  accept-all row carries an unresolved suggestion, so each row resolves here first (a no-op
     *  when already resolved). */
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
            try {
                targets.forEach { row ->
                    if (state.value.cancelRequested) return@forEach
                    val failed = try {
                        val resolved = adapter.resolve(row.chosen!!) ?: error("target failed to resolve")
                        adapter.migrate(row.entry, resolved, replace, flags)
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "Migration failed for ${row.entry.id}" }
                        true
                    }
                    if (failed) failures++
                    setRow(row.entry.id) { it.copy(migratedOk = !failed, failed = failed) }
                    mutableState.update { it.copy(progressDone = it.progressDone + 1) }
                }
            } finally {
                mutableState.update {
                    it.copy(
                        isMigrating = false,
                        finished = failures == 0 && !it.cancelRequested && it.progressDone == it.progressTotal,
                        cancelRequested = false,
                    )
                }
            }
        }
    }

    /** Cancels the in-flight row too (the engines rethrow cancellation), not just the queue. */
    fun cancelCommit() {
        mutableState.update { it.copy(cancelRequested = true) }
        commitJob?.cancel()
    }

    /** Retry one failed row with the last commit's flags and verb. */
    fun retryRow(id: EntryId) {
        val st = state.value
        val row = st.rows.firstOrNull { it.entry.id == id } ?: return
        val target = row.chosen ?: return
        val flags = st.lastFlags ?: return
        setRow(id) { it.copy(failed = false) }
        screenModelScope.launchIO {
            val result = runCatching { adapter.migrate(row.entry, target, st.lastReplace, flags) }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Migration retry failed for ${row.entry.id}" } }
            setRow(id) { it.copy(migratedOk = result.isSuccess, failed = result.isFailure) }
            if (result.isSuccess && state.value.rows.none { it.failed }) {
                mutableState.update { it.copy(finished = true) }
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
            val candidate = runCatching { adapter.storedCandidate(targetRawId) }.getOrNull()
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
        val rows: List<Row> = emptyList(),
        val isMigrating: Boolean = false,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val cancelRequested: Boolean = false,
        val finished: Boolean = false,
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
                    val targetCount = row.suggested?.chapterCount
                    val currentCount = row.entry.chapterCount
                    if (targetCount != null && currentCount != null && targetCount <= currentCount) {
                        return@filter false
                    }
                }
                true
            }
        val chosenCount: Int get() = rows.count { it.chosen != null && !it.skipped && !it.migratedOk }
        val skippedCount: Int get() = rows.size - rows.count { it.chosen != null && !it.skipped }
        val hasUnacceptedSuggestions: Boolean
            get() = rows.any { it.chosen == null && !it.skipped && it.suggested != null }
        val searchedCount: Int get() = rows.count { it.searchStarted && !it.searching }
        val hasFailures: Boolean get() = rows.any { it.failed }
    }
}
