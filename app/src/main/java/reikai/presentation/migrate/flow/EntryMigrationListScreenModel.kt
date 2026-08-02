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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/** Max sources hit concurrently by one fan-out (a row's smart-match sweep, or one override search). */
private const val SEARCH_CONCURRENCY = 5

/**
 * The unified migration list for 1..N entries of one content type, over the [MigrationFlowAdapter]
 * seam. The batch auto-search runs one row at a time in list order (upstream's proven shape: a
 * source never sees more than one regular request, and rows complete top-down), while the
 * interactive paths (override search, accept-resolve) run off the batch queue so they respond
 * immediately. The user accepts the suggested match or overrides it inline; the batch commits
 * through the adapter with per-row failure capture and retry. Behavior and UI contract: the design
 * note in docs/dev/plans/content-layer-migrate-surface.md.
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

    /** Bumped on every batch restart (and on tuning changes) so a superseded loop's writes land as
     *  no-ops. Volatile: written on main (applyTuning) and under [batchMutex], read from IO loops
     *  and inside state-update lambdas. */
    @Volatile
    private var searchGeneration = 0

    /** The sequential batch searcher; cancelled and restarted by [applyTuning] and skip. */
    @Volatile
    private var batchJob: Job? = null

    /** The generation [batchJob] was launched for: after an un-joined cancel the old job can still
     *  read as active while stuck in a source call, so "already running" is only true for a job of
     *  the current generation. */
    @Volatile
    private var batchJobGeneration = -1

    /** Interactive override searches, bounded separately from the batch so expanding a row responds
     *  immediately instead of queueing behind it; one job per row so a re-search cancels its
     *  predecessor instead of racing it. */
    private val interactiveSemaphore = Semaphore(SEARCH_CONCURRENCY)
    private val researchJobs = ConcurrentHashMap<EntryId, Job>()
    private var commitJob: Job? = null

    /** Versions the confirm dialog's flag scan; see [showConfirm]. */
    @Volatile
    private var confirmScanId = 0

    /** Serializes every batch stop/start: an unsynchronized restart pair could interleave so the
     *  surviving loop carried a stale generation and exited with every row reset and no batch
     *  running (permanent 0/N, no commit bar). */
    private val batchMutex = Mutex()

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
     *  enabled set (a since-disabled saved source drops out); when nothing is saved, or the saved
     *  set resolves to nothing (every saved source currently disabled or uninstalled), pinned
     *  sources lead the enabled set. The row's own source stays searchable; the adapters reject its
     *  identical listing. */
    private fun sourcesFor(row: Row): List<MigrationSourceUi> {
        val enabled = adapter.enabledSources()
        val saved = adapter.savedSelection()
        val resolved = run {
            val byKey = enabled.associateBy { it.key }
            saved.mapNotNull { byKey[it] }
        }
        return resolved.ifEmpty {
            val pinned = adapter.pinnedKeys()
            enabled.sortedBy { it.key !in pinned }
        }
    }

    /** Launch (or resume) the sequential batch search over every not-yet-started, unskipped row.
     *  One row's failure marks that row done and moves on; a throw must never kill the loop, or the
     *  remaining rows would silently never search and the all-searched commit gate never open. */
    private fun startSearches() {
        screenModelScope.launchIO { batchMutex.withLock { startSearchesLocked() } }
    }

    private fun startSearchesLocked() {
        if (batchJob?.isActive == true && batchJobGeneration == searchGeneration) return
        val generation = searchGeneration
        batchJobGeneration = generation
        batchJob = screenModelScope.launchIO {
            while (generation == searchGeneration) {
                val next = state.value.rows.firstOrNull { !it.searchStarted && !it.skipped } ?: break
                try {
                    searchRow(next, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Row search failed for ${next.entry.id}" }
                    // Failed, not "no match": hide-unmatched must not bury an errored row.
                    setRowForGeneration(generation, next.entry.id) {
                        it.copy(searching = false, searchFailed = true)
                    }
                }
            }
        }
    }

    /** Cancel the batch and resume WITHOUT joining the cancelled loop: a join parked this mutex
     *  behind a source call that may never honour cancellation, freezing every batch control
     *  (including skip, the escape hatch for exactly that row). Instead the generation bump turns
     *  the old loop's writes into no-ops ([setRowForGeneration] checks inside the state update, so
     *  a zombie can't re-mark a row searching after the reset), and the reset clears anything it
     *  left mid-flight. */
    private fun restartBatch(afterStopped: () -> Unit = {}) {
        screenModelScope.launchIO {
            batchMutex.withLock {
                searchGeneration++
                batchJob?.cancel()
                // Clear any row the cancelled loop left mid-flight so the resumed loop re-searches it.
                mutableState.update { st ->
                    st.copy(
                        rows = st.rows.map {
                            if (it.searching) it.copy(searchStarted = false, searching = false) else it
                        },
                    )
                }
                afterStopped()
                startSearchesLocked()
            }
        }
    }

    /** A batch-loop row write that re-checks the generation INSIDE the state update (the lambda
     *  re-runs on a CAS retry, so the check holds even against a concurrent bump). This is what
     *  makes the un-joined restart safe: a superseded loop resuming from a stuck source call gets
     *  a no-op, not a stranded row. */
    private inline fun setRowForGeneration(generation: Int, id: EntryId, crossinline transform: (Row) -> Row) {
        mutableState.update { st ->
            if (generation != searchGeneration) return@update st
            st.copy(rows = st.rows.map { if (it.entry.id == id) transform(it) else it })
        }
    }

    private suspend fun searchRow(row: Row, generation: Int) {
        val id = row.entry.id
        setRowForGeneration(generation, id) { it.copy(searchStarted = true, searching = true, searchFailed = false) }
        val tuning = state.value.tuning
        val sources = sourcesFor(row)
        // Per-source failures are counted here, where they are swallowed: "every source errored" must
        // surface as a failed search, not pose as "no match" (which hide-unmatched would then bury).
        var errors = 0
        val suggestion = if (tuning.prioritizeByChapters && adapter.supportsSmartMatch) {
            // Fan this row's sources out in parallel (a local bound, matching upstream's per-manga
            // semaphore) and keep the target furthest ahead by latest chapter number, upstream's
            // comparison basis (row counts lie across sources that split/bundle chapters). A
            // zero-chapter hit is never suggested, matching upstream.
            val fanOut = Semaphore(SEARCH_CONCURRENCY)
            val probes = coroutineScope {
                sources.map { source ->
                    async {
                        source to fanOut.withPermit {
                            runCatchingCancellable { adapter.suggest(row.entry, source.key, tuning) }
                        }
                    }
                }.awaitAll()
            }
            errors = probes.count { (_, result) -> result.isFailure }
            probes.mapNotNull { (source, result) ->
                result.getOrNull()?.takeIf { (it.chapterCount ?: 0) > 0 }?.let { source to it }
            }.maxByOrNull { (_, candidate) -> candidate.latestChapter ?: 0.0 }
        } else {
            // Priority order: the first selected source with a hit is the suggestion.
            var hit: Pair<MigrationSourceUi, MigrationCandidate>? = null
            for (source in sources) {
                val result = runCatchingCancellable { adapter.suggest(row.entry, source.key, tuning) }
                if (result.isFailure) {
                    errors++
                    continue
                }
                val candidate = result.getOrNull() ?: continue
                hit = source to candidate
                break
            }
            hit
        }
        val allSourcesFailed = suggestion == null && sources.isNotEmpty() && errors == sources.size
        setRowForGeneration(generation, id) {
            it.copy(
                searching = false,
                searchFailed = allSourcesFailed,
                suggested = suggestion?.second,
                suggestedSourceName = suggestion?.first?.name,
            )
        }
    }

    /** Re-run a row's search with a user-edited query, filling the override strips. Sources run in
     *  parallel under the interactive bound; a failed source keeps its strip with the error text
     *  instead of disappearing into "no results". A re-search cancels the previous run, and the
     *  final write self-checks it is still the row's registered job INSIDE the state update: a
     *  cancel landing after awaitAll returned cannot stop the straight-line tail, so without the
     *  check a superseded run's stale strips could land after [applyTuning]'s reset (and then block
     *  the expand-transition re-search). */
    fun research(id: EntryId, query: String) {
        if (query.isBlank()) return
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        // The saved extra query narrows the override search exactly as it narrows the batch search.
        val fullQuery = listOfNotNull(
            query.trim(),
            state.value.tuning.extraQuery?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        researchJobs[id]?.cancel()
        setRow(id) { it.copy(overrideLoading = true, overrideStrips = emptyList()) }
        researchJobs[id] = screenModelScope.launchIO {
            val myJob = coroutineContext[Job]
            val strips = coroutineScope {
                sourcesFor(row).map { source ->
                    async {
                        interactiveSemaphore.withPermit {
                            runCatchingCancellable { adapter.candidates(row.entry, fullQuery, source.key) }
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
            mutableState.update { st ->
                if (researchJobs[id] !== myJob) return@update st
                st.copy(
                    rows = st.rows.map {
                        if (it.entry.id == id) it.copy(overrideLoading = false, overrideStrips = strips) else it
                    },
                )
            }
        }
    }

    /** Accept the suggestion (or un-accept a chosen target back to suggested). */
    fun toggleAccept(id: EntryId) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        // A migrated row's target is history: un-accepting it would strand the row (re-accept
        // dead-ends on pick's own guard).
        if (row.migratedOk || row.committing || row.resolving) return
        if (row.chosen != null) {
            setRow(id) { it.copy(chosen = null, chosenSourceName = null) }
            return
        }
        val suggested = row.suggested ?: return
        pick(id, suggested, row.suggestedSourceName)
    }

    /** Pick a candidate (suggested or from an override strip) and resolve it into the row's target. */
    fun pick(id: EntryId, candidate: MigrationCandidate, sourceName: String?) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        // A migrated or mid-commit row takes no new target; a late pick write-back would restore a
        // chosen candidate onto a row already marked migrated.
        if (row.migratedOk || row.committing || row.resolving) return
        setRow(id) { it.copy(resolving = true) }
        screenModelScope.launchIO {
            val resolved = runCatchingCancellable { adapter.resolve(candidate) }.getOrNull()
            // State first, event second: the send suspends until the screen's collector is live (a
            // rendezvous channel), and with the list off-composition behind a pushed picker that
            // would leave the row resolving forever, holding the commit gates shut.
            setRow(id) {
                if (it.migratedOk) return@setRow it.copy(resolving = false)
                it.copy(
                    resolving = false,
                    chosen = resolved,
                    chosenSourceName = sourceName.takeIf { _ -> resolved != null },
                    expanded = false,
                    skipped = if (resolved != null) false else it.skipped,
                )
            }
            if (resolved == null) _events.send(Event.PickFailed)
        }
    }

    /** Accept every visible suggested match with no chosen target yet; resolution happens at commit,
     *  so a large list accepts instantly. Visible only: a row the hide toggles filtered out must not
     *  be accepted behind the user's back (accepting would pop it back into view). */
    fun acceptAllSuggestions() {
        // Same busy gate as the other commit-adjacent writes: a bulk accept during a commit could
        // write `chosen` onto the row being committed.
        if (state.value.isMigrating || state.value.hasActiveSingleCommit) return
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

    /** Skip keeps the chosen target so unskip restores the row exactly (the design's "restorable"),
     *  and is the escape hatch for a stuck row: a skipped row stops counting toward the all-searched
     *  commit gate, and skipping the row currently in flight excises it from the batch immediately
     *  (upstream's remove-from-batch mechanics under our restorable presentation). Restoring an
     *  unsearched row re-queues its search. */
    fun toggleSkip(id: EntryId) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        // A skip landing on a mid-commit row would outlive the commit's completion write (which
        // doesn't touch `skipped`) as a dimmed "Migrated" row with no restore path.
        if (row.committing || row.migratedOk) return
        val skipping = !row.skipped
        setRow(id) { it.copy(skipped = skipping) }
        if (skipping && row.searching) {
            restartBatch()
        } else if (!skipping && !row.searchStarted) {
            // Through the restart (not a bare start): a start landing while the finished loop is
            // still winding down would early-return and the restored row would never search.
            restartBatch()
        }
    }

    /** Expanding also kicks the first override search (model-owned, not a composable effect: a
     *  scroll-away and back must not re-fire it, and it applies the saved extra query). */
    fun toggleExpanded(id: EntryId) {
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        val expanding = !row.expanded
        setRow(id) { it.copy(expanded = expanding) }
        if (expanding && row.overrideStrips.isEmpty() && !row.overrideLoading) {
            research(id, row.entry.title)
        }
    }

    /** Persist edited tuning and re-run every unmigrated row's search under it. The superseded
     *  batch is cancelled and abandoned (see [restartBatch]); the generation bump drops any write
     *  already past its cancellation point. Blocked while a commit runs: the reset would blank rows
     *  the commit is reading. */
    fun applyTuning(tuning: MigrationTuning): Boolean {
        if (state.value.isMigrating || state.value.hasActiveSingleCommit || state.value.hasActiveResolve) {
            return false
        }
        adapter.persistTuning(tuning)
        // Written synchronously: the restart below runs async, and a reopened sheet must seed from
        // the new values, not re-apply the old ones over the pref.
        mutableState.update { it.copy(tuning = tuning) }
        searchGeneration++
        // Old-tuning override searches die with the batch: their late writes would repopulate the
        // strips this reset clears.
        researchJobs.values.forEach { it.cancel() }
        researchJobs.clear()
        restartBatch {
            mutableState.update { st ->
                st.copy(
                    rows = st.rows.map {
                        // A migrated row's result is history, not a suggestion; re-searching it
                        // would blank what it migrated to.
                        if (it.migratedOk) {
                            it
                        } else {
                            // Collapsed too: the first override search fires on the expand
                            // transition, so a row left expanded over cleared strips would sit on
                            // "no results" with nothing re-searching it.
                            it.copy(
                                searchStarted = false,
                                searching = false,
                                searchFailed = false,
                                suggested = null,
                                suggestedSourceName = null,
                                overrideStrips = emptyList(),
                                overrideLoading = false,
                                expanded = false,
                            )
                        }
                    },
                )
            }
        }
        return true
    }

    /** Open the confirm dialog immediately; the applicable-flag scan (per-entry chapter and
     *  download reads) fills in async so the button never looks dead. Scans are versioned: a
     *  dismiss-and-reopen must not let the older scan's late result rewrite the newer dialog's
     *  flags (the seed is also the checkbox state's reset key). */
    fun showConfirm(replace: Boolean) {
        val scanId = ++confirmScanId
        mutableState.update {
            it.copy(showConfirm = true, confirmReplace = replace, confirmFlagsLoading = true)
        }
        screenModelScope.launchIO {
            // The same set commit() will target: an already-migrated row must not contribute flags.
            val chosenRows = state.value.rows.filter { it.chosen != null && !it.skipped && !it.migratedOk }
            val applicable = adapter.applicableFlags(chosenRows.map { it.entry })
            // Re-read the saved set: a commit earlier in this session rewrote the pref, and the
            // one-shot init snapshot would reseed the second confirm with stale checks.
            val saved = adapter.savedFlags()
            mutableState.update {
                if (scanId != confirmScanId) return@update it
                it.copy(confirmFlagsLoading = false, applicableFlags = applicable, initialFlags = saved)
            }
        }
    }

    fun dismissConfirm() = mutableState.update { it.copy(showConfirm = false) }

    /** Commit every chosen row; a failed row stays in place with an error and a retry. An
     *  accept-all row carries an unresolved suggestion, so each row resolves here first (a no-op
     *  when already resolved); the resolved target is written back so retry commits it, not the
     *  unresolved suggestion (which the novel adapter rejects). */
    fun commit(flags: Set<MigrationDataFlag>, replace: Boolean) {
        // One commit path at a time: re-entry (a double-tapped confirm) and an in-flight single-row
        // commit would migrate the same row twice; an in-flight pick resolve would be snapshotted
        // at its previous target. Close the dialog on the guarded path: leaving it open with a
        // dead confirm button read as a hang.
        if (state.value.isMigrating || state.value.hasActiveSingleCommit || state.value.hasActiveResolve) {
            dismissConfirm()
            return
        }
        val targets = state.value.rows.filter { it.chosen != null && !it.skipped && !it.migratedOk }
        mutableState.update {
            it.copy(
                showConfirm = false,
                isMigrating = true,
                progressDone = 0,
                progressTotal = targets.size,
                lastFlags = flags,
                lastReplace = replace,
                // A cancel can race the previous commit's finally and survive it; a stale flag here
                // would silently skip every row of this commit.
                cancelRequested = false,
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
                        setRow(row.entry.id) {
                            it.copy(
                                chosen = resolved ?: it.chosen,
                                failed = true,
                                failedReplace = replace,
                                failedFlags = flags,
                                failedInBatch = true,
                            )
                        }
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) { "Migration failed for ${row.entry.id}" }
                        true
                    }
                    if (failed) failures++ else succeeded++
                    setRow(row.entry.id) {
                        it.copy(
                            chosen = resolved ?: it.chosen,
                            migratedOk = !failed,
                            failed = failed,
                            failedReplace = replace.takeIf { _ -> failed },
                            failedFlags = flags.takeIf { _ -> failed },
                            failedInBatch = failed,
                        )
                    }
                    mutableState.update { it.copy(progressDone = it.progressDone + 1) }
                }
            } finally {
                mutableState.update {
                    it.copy(
                        isMigrating = false,
                        finishedCount = it.finishedCount + succeeded,
                        // Every queued row done and none failed is finished, even if a cancel landed
                        // after the last row (withholding the pop there stranded a complete commit).
                        finished = failures == 0 && it.progressTotal > 0 && it.progressDone == it.progressTotal,
                        cancelRequested = false,
                    )
                }
            }
        }
    }

    /** Cancels the in-flight row too (the engines rethrow cancellation), not just the queue; the
     *  interrupted row is marked failed by [commit] so it stays visible and retryable. A cancel
     *  after the commit already finished is ignored (a sticky flag would skip the next commit). */
    fun cancelCommit() {
        if (!state.value.isMigrating) return
        mutableState.update { it.copy(cancelRequested = true) }
        commitJob?.cancel()
    }

    /** Commit one row immediately with the saved flags (the per-row "Migrate now" / "Copy now").
     *  Single commits are serialized (any active one blocks the next): the manga engine reads its
     *  flag set from a global pref, so two concurrent commits would cross-contaminate flags. */
    fun commitSingle(id: EntryId, replace: Boolean) {
        if (state.value.isMigrating || state.value.hasActiveSingleCommit) return
        val row = state.value.rows.firstOrNull { it.entry.id == id } ?: return
        val target = row.chosen ?: row.suggested ?: return
        // Captured at tap time: a concurrent commit path rewriting the pref during our resolve must
        // not swap the flag set under this commit.
        val flags = adapter.savedFlags()
        setRow(id) { it.copy(resolving = true, committing = true, failed = false) }
        screenModelScope.launchIO {
            val result = try {
                runCatchingCancellable {
                    val resolved = adapter.resolve(target) ?: error("target failed to resolve")
                    adapter.migrate(row.entry, resolved, replace, flags)
                    resolved
                }
            } catch (e: CancellationException) {
                // Mirrors the batch: the engines are not transactional, so a cancelled row may be
                // half-applied and must surface failed/retryable, not committing forever.
                setRow(id) {
                    it.copy(
                        resolving = false,
                        committing = false,
                        failed = true,
                        failedReplace = replace,
                        failedFlags = flags,
                        failedInBatch = false,
                    )
                }
                throw e
            }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Single-row migration failed for ${row.entry.id}" } }
            setRow(id) {
                it.copy(
                    resolving = false,
                    committing = false,
                    chosen = result.getOrNull() ?: target,
                    chosenSourceName = it.chosenSourceName ?: it.suggestedSourceName,
                    migratedOk = result.isSuccess,
                    failed = result.isFailure,
                    // Remember the verb AND flags so a retry repeats THIS commit, not the last batch's.
                    failedReplace = replace.takeIf { _ -> result.isFailure },
                    failedFlags = flags.takeIf { _ -> result.isFailure },
                    failedInBatch = false,
                )
            }
            if (result.isSuccess) {
                mutableState.update { it.copy(finishedCount = it.finishedCount + 1) }
            }
        }
    }

    /** Retry one failed row with the verb of the commit that failed it and the last flags (saved
     *  flags when the failure came from a single-row commit). Resolves first: the stored chosen may
     *  still be the unresolved suggestion when the original resolve was the step that failed. */
    fun retryRow(id: EntryId) {
        if (state.value.isMigrating || state.value.hasActiveSingleCommit) return
        val st = state.value
        val row = st.rows.firstOrNull { it.entry.id == id } ?: return
        val target = row.chosen ?: return
        val replace = row.failedReplace ?: st.lastReplace
        val flags = row.failedFlags ?: st.lastFlags ?: adapter.savedFlags()
        setRow(id) { it.copy(failed = false, resolving = true, committing = true) }
        screenModelScope.launchIO {
            val result = try {
                runCatchingCancellable {
                    val resolved = adapter.resolve(target) ?: error("target failed to resolve")
                    adapter.migrate(row.entry, resolved, replace, flags)
                    resolved
                }
            } catch (e: CancellationException) {
                // Mirrors the batch: a cancelled retry may be half-applied; keep it failed/retryable.
                setRow(id) {
                    it.copy(
                        resolving = false,
                        committing = false,
                        failed = true,
                        failedReplace = replace,
                        failedFlags = flags,
                        failedInBatch = it.failedInBatch,
                    )
                }
                throw e
            }
            result.onFailure { logcat(LogPriority.ERROR, it) { "Migration retry failed for ${row.entry.id}" } }
            setRow(id) {
                it.copy(
                    resolving = false,
                    committing = false,
                    chosen = result.getOrNull() ?: target,
                    migratedOk = result.isSuccess,
                    failed = result.isFailure,
                    failedReplace = replace.takeIf { _ -> result.isFailure },
                    failedFlags = flags.takeIf { _ -> result.isFailure },
                    failedInBatch = it.failedInBatch && result.isFailure,
                )
            }
            if (result.isSuccess) {
                mutableState.update { it.copy(finishedCount = it.finishedCount + 1) }
                // Only a BATCH failure's retry finishes the screen (a single-row commit's retry
                // leaves the user in the list to keep working), and another retry still in flight
                // (committing) must finish first or the pop would cancel it mid-migration.
                val now = state.value
                if (row.failedInBatch && !now.isMigrating && !now.hasActiveSingleCommit &&
                    now.rows.none { it.failed }
                ) {
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
     *  resolved candidate without a search. The entry's own row is never a valid target (the engines
     *  would silently no-op and the row would read as migrated). Guarded and marked resolving like
     *  [pick]: this is a commit-path write, and it must be visible to the commit gates and never
     *  re-arm a row that migrated while the picker was open. */
    fun overrideWithStored(currentRawId: Long, targetRawId: Long) {
        val row = state.value.rows.firstOrNull { it.entry.id.rawId == currentRawId }
        if (row == null || currentRawId == targetRawId || row.migratedOk || row.committing || row.resolving) {
            screenModelScope.launchIO { _events.send(Event.PickFailed) }
            return
        }
        setRow(row.entry.id) { it.copy(resolving = true) }
        screenModelScope.launchIO {
            val candidate = runCatchingCancellable { adapter.storedCandidate(targetRawId) }.getOrNull()
            if (candidate == null) {
                setRow(row.entry.id) { it.copy(resolving = false) }
                _events.send(Event.PickFailed)
                return@launchIO
            }
            setRow(row.entry.id) {
                if (it.migratedOk) return@setRow it.copy(resolving = false)
                it.copy(
                    resolving = false,
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
        /** A single-row commit or retry is in flight; blocks the batch and a second tap. */
        val committing: Boolean = false,
        val chosen: MigrationCandidate? = null,
        val chosenSourceName: String? = null,
        val skipped: Boolean = false,
        val expanded: Boolean = false,
        val migratedOk: Boolean = false,
        val failed: Boolean = false,
        /** The row's SEARCH threw (distinct from a commit failure): shown on the meta line and
         *  exempt from hide-unmatched, which must not bury an errored row as "no match". */
        val searchFailed: Boolean = false,
        /** The verb of the commit that failed this row, so retry repeats it (a failed Copy must
         *  never retry as a replace). */
        val failedReplace: Boolean? = null,
        /** The flag set of the commit that failed this row, so retry repeats it (a single-row
         *  commit's saved flags must not be replaced by an earlier batch's set). */
        val failedFlags: Set<MigrationDataFlag>? = null,
        /** Whether the failing commit was the batch: only a batch failure's retry may finish the
         *  screen when it was the last one standing. */
        val failedInBatch: Boolean = false,
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
        val confirmFlagsLoading: Boolean = false,
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
                if (tuning.hideUnmatched && row.searchStarted && !row.searching &&
                    row.suggested == null && !row.searchFailed
                ) {
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

        /** Over the visible rows, so the accept-all action disables once the hide toggles have
         *  filtered every unaccepted suggestion out of sight. */
        val hasUnacceptedSuggestions: Boolean
            get() = visibleRows.any { it.chosen == null && !it.skipped && it.suggested != null }
        val hasActiveSingleCommit: Boolean get() = rows.any { it.committing }

        /** A pick/accept resolve in flight; the commit-bar buttons disable so a commit can't
         *  snapshot the row's previous target while the new one is still resolving. */
        val hasActiveResolve: Boolean get() = rows.any { it.resolving }

        /** Skipped rows count as settled: skip is the escape hatch for a source that hangs, so a
         *  skipped row must not hold the searched count or the commit gate hostage. */
        val searchedCount: Int get() = rows.count { it.skipped || (it.searchStarted && !it.searching) }
        val allSearched: Boolean
            get() = rows.isNotEmpty() && rows.all { it.skipped || (it.searchStarted && !it.searching) }
    }
}
