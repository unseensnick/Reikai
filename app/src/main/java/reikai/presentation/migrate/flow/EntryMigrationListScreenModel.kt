package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
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
 * capture and retry. Behavior and UI contract: docs/dev/plans/content-layer-migrate-surface.md,
 * "The step-2 design note".
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

    init {
        screenModelScope.launchIO {
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
     *  enabled set (a since-disabled saved source drops out), minus the row's own source. */
    private fun sourcesFor(row: Row): List<MigrationSourceUi> {
        val enabled = adapter.enabledSources()
        val saved = adapter.savedSelection()
        val ordered = if (saved.isEmpty()) {
            enabled
        } else {
            val byKey = enabled.associateBy { it.key }
            saved.mapNotNull { byKey[it] }
        }
        return ordered.filter { it.key != row.entry.sourceKey }
    }

    /** Auto-search a row the first time it composes (idempotent). */
    fun searchRow(id: EntryId) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        if (row.searchStarted) return
        setRow(id) { it.copy(searchStarted = true, searching = true) }
        screenModelScope.launchIO {
            val tuning = state.value.tuning
            val sources = sourcesFor(row)
            val resolveCounts = tuning.prioritizeByChapters || tuning.hideWithoutUpdates
            val suggestion = if (tuning.prioritizeByChapters && adapter.supportsSmartMatch) {
                // Fan every source out and keep the target with the most chapters.
                sources.mapNotNull { source ->
                    searchSemaphore.withPermit {
                        runCatching { adapter.suggest(row.entry, source.key, tuning) }.getOrNull()
                    }?.let { source to it }
                }
                    .mapNotNull { (source, candidate) ->
                        adapter.resolve(candidate)?.let { source to it }
                    }
                    .maxByOrNull { (_, candidate) -> candidate.chapterCount ?: -1 }
            } else {
                // Priority order: the first selected source with a hit is the suggestion.
                sources.firstNotNullOfOrNull { source ->
                    searchSemaphore.withPermit {
                        runCatching { adapter.suggest(row.entry, source.key, tuning) }.getOrNull()
                    }?.let { candidate ->
                        if (resolveCounts) {
                            adapter.resolve(candidate)?.let { source to it }
                        } else {
                            source to candidate
                        }
                    }
                }
            }
            setRow(id) {
                it.copy(
                    searching = false,
                    suggested = suggestion?.second,
                    suggestedSourceName = suggestion?.first?.name,
                )
            }
        }
    }

    /** Re-run a row's search with a user-edited query, filling the override strips. */
    fun research(id: EntryId, query: String) {
        if (query.isBlank()) return
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        setRow(id) { it.copy(overrideLoading = true) }
        screenModelScope.launchIO {
            val strips = sourcesFor(row).map { source ->
                val candidates = searchSemaphore.withPermit {
                    runCatching { adapter.candidates(row.entry, query, source.key) }.getOrDefault(emptyList())
                }
                OverrideStrip(sourceName = source.name, candidates = candidates)
            }.filter { it.candidates.isNotEmpty() }
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

    fun toggleSkip(id: EntryId) = setRow(id) {
        it.copy(skipped = !it.skipped, chosen = null, chosenSourceName = null)
    }

    fun toggleExpanded(id: EntryId) = setRow(id) { it.copy(expanded = !it.expanded) }

    /** Persist edited tuning and re-run every row's search under it. */
    fun applyTuning(tuning: MigrationTuning) {
        adapter.persistTuning(tuning)
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

    /** Commit every chosen row; a failed row stays in place with an error and a retry. */
    fun commit(flags: Set<MigrationDataFlag>, replace: Boolean) {
        mutableState.update {
            it.copy(showConfirm = false, isMigrating = true, progressDone = 0, lastFlags = flags, lastReplace = replace)
        }
        screenModelScope.launchIO {
            val targets = state.value.rows.filter { it.chosen != null && !it.skipped && !it.migratedOk }
            var failures = 0
            targets.forEach { row ->
                if (state.value.cancelRequested) return@forEach
                val result = runCatching { adapter.migrate(row.entry, row.chosen!!, replace, flags) }
                result.onFailure { logcat(LogPriority.ERROR, it) { "Migration failed for ${row.entry.id}" } }
                if (result.isFailure) failures++
                setRow(row.entry.id) { it.copy(migratedOk = result.isSuccess, failed = result.isFailure) }
                mutableState.update { it.copy(progressDone = it.progressDone + 1) }
            }
            mutableState.update {
                it.copy(
                    isMigrating = false,
                    cancelRequested = false,
                    finished = failures == 0 && !it.cancelRequested,
                )
            }
        }
    }

    fun cancelCommit() = mutableState.update { it.copy(cancelRequested = true) }

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

    data class OverrideStrip(
        val sourceName: String,
        val candidates: List<MigrationCandidate>,
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
        val cancelRequested: Boolean = false,
        val finished: Boolean = false,
        val showConfirm: Boolean = false,
        val confirmReplace: Boolean = true,
        val initialFlags: Set<MigrationDataFlag> = MigrationDataFlag.entries.toSet(),
        val applicableFlags: Set<MigrationDataFlag> = MigrationDataFlag.entries.toSet(),
        val lastFlags: Set<MigrationDataFlag>? = null,
        val lastReplace: Boolean = true,
    ) {
        /** Rows after the hide toggles; the scroll list renders these. */
        val visibleRows: List<Row>
            get() = rows.filter { row ->
                if (tuning.hideUnmatched && row.searchStarted && !row.searching &&
                    row.suggested == null && row.chosen == null
                ) {
                    return@filter false
                }
                if (tuning.hideWithoutUpdates) {
                    val targetCount = (row.chosen ?: row.suggested)?.chapterCount
                    val currentCount = row.entry.chapterCount
                    if (targetCount != null && currentCount != null && targetCount <= currentCount) {
                        return@filter false
                    }
                }
                true
            }
        val chosenCount: Int get() = rows.count { it.chosen != null && !it.skipped && !it.migratedOk }
        val skippedCount: Int get() = rows.size - rows.count { it.chosen != null && !it.skipped }
        val progressTotal: Int get() = chosenCount
        val searchedCount: Int get() = rows.count { it.searchStarted && !it.searching }
        val hasFailures: Boolean get() = rows.any { it.failed }
    }
}
