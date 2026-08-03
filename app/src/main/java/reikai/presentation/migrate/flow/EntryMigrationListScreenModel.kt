package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.migrate.flow.MigratingEntryRow.CommitPhase
import reikai.presentation.migrate.flow.MigratingEntryRow.SearchPhase
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Sources probed at once while ranking one row's matches by chapter count. */
private const val SOURCE_CONCURRENCY = 5

/**
 * Drives a migration batch for one content type over [MigrationFlowAdapter].
 *
 * Rows are searched one at a time in list order, so a source never sees more than one request from
 * the batch and rows settle top-down. The loop re-checks each row at its head instead of holding a
 * lock: a row can be skipped, committed or abandoned while the loop is awaiting the previous one,
 * and every one of those shows up as a state the head check reads. Per-row work runs on the row's
 * own detached scope ([MigratingEntryRow.scope]), so cancelling one row never reaches the loop.
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

    private val pickHandoff: MigrationPickHandoff = Injekt.get()

    private val rows: List<MigratingEntryRow> get() = state.value.rows

    private var commitJob: Job? = null

    /**
     * The rows the last batch commit was responsible for. The screen only ever finishes itself when
     * every one of them has been resolved, so a batch that was cancelled or left failures keeps the
     * list open until the user deals with its rows. Anything they do afterwards is new work, not the
     * batch's, which is why a lone "Migrate now" cannot close a list they are still working.
     */
    @Volatile
    private var batchTargets: List<MigratingEntryRow> = emptyList()

    /** The sequential search driver; idle once every row has settled. */
    @Volatile
    private var searchJob: Job? = null

    /** Override searches are bounded separately from the batch, so opening a row responds at once
     *  rather than waiting for the batch's turn to come round. */
    private val interactiveSearches = Semaphore(SOURCE_CONCURRENCY)

    /** Versions the confirm dialog's flag scan so a dismissed scan cannot land on a later dialog. */
    @Volatile
    private var confirmScanId = 0

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val tuning = adapter.readTuning().copy(extraQuery = extraQuery)
            val built = adapter.loadEntries(entryIds).map {
                MigratingEntryRow(entry = it, parentContext = screenModelScope.coroutineContext)
            }
            mutableState.update {
                it.copy(
                    isLoading = false,
                    tuning = tuning,
                    rows = built,
                    visibleRows = built,
                    supportsSmartMatch = adapter.supportsSmartMatch,
                    savedFlags = adapter.savedFlags(),
                )
            }
            startDriver()
        }
    }

    /** Run the search driver unless it is already running. Restoring a skipped row calls this too,
     *  since the driver stops once nothing is left to search. Synchronized so two callers on
     *  different threads cannot both pass the liveness check and start twin drivers. */
    @Synchronized
    private fun startDriver() {
        if (searchJob?.isActive == true) return
        searchJob = screenModelScope.launchIO { drive() }
    }

    /**
     * Search whatever is eligible, one row at a time, until nothing is. The next row is chosen on
     * each pass rather than iterating a fixed list, so a row skipped mid-run drops out and a
     * restored row is picked up without a second driver.
     */
    private suspend fun drive() {
        val sources = sourcesFor()
        val tuning = state.value.tuning
        while (currentCoroutineContext().isActive) {
            val row = nextSearchable() ?: break
            // Claim the row atomically: if a second driver ever overlaps this one, only the winner
            // searches, and the loser moves on.
            if (!row.search.compareAndSet(SearchPhase.Queued, SearchPhase.Searching)) continue
            syncCounts()

            val deferred = row.scope.async { search(row, sources, tuning) }
            val outcome = try {
                deferred.await()
            } catch (_: CancellationException) {
                // The row was abandoned mid-search (skipped, or this driver was cancelled under a
                // tuning re-run). Stop the search itself too, and put the row back in the queue.
                deferred.cancel()
                row.search.compareAndSet(SearchPhase.Searching, SearchPhase.Queued)
                syncCounts()
                // A cancelled driver can have claimed a row rebuilt by tuning between its liveness
                // check and the claim, after the new driver already scanned past it. Handing the
                // row to a live driver (no-op when one is running) keeps it from queueing forever.
                if (rows.any { it === row }) startDriver()
                continue
            }
            row.search.value = outcome
            syncCounts()
            peekSuggestionCounts(row)
        }
        handOffIfWorkRemains()
    }

    /** The row the driver should search next, or null when nothing is eligible. */
    private fun nextSearchable(): MigratingEntryRow? = rows.firstOrNull {
        MigrationRowRules.canSearch(it.search.value, it.commit.value, it.skipped.value) && it.scope.isActive
    }

    /**
     * Restart the driver if a row became searchable on the way out.
     *
     * The loop's last scan and [startDriver]'s liveness check are not atomic: a row handed back
     * between them sees a job that is still active, so the restart is skipped and the row sits
     * queued for the rest of the session with the commit bar shut. Joining this driver first means
     * the restart runs against a completed job and actually takes.
     */
    private fun handOffIfWorkRemains() {
        val finishing = searchJob
        if (nextSearchable() == null) return
        screenModelScope.launchIO {
            finishing?.join()
            startDriver()
        }
    }

    /**
     * Fill a found suggestion's chapter counts in the background, so the compare reads
     * "584 -> 601" instead of unknown before the user decides. Display-only and best-effort;
     * runs in the row's own scope, and a result whose row moved on is dropped, not applied.
     */
    private fun peekSuggestionCounts(row: MigratingEntryRow) {
        val found = row.search.value as? SearchPhase.Found ?: return
        val suggestion = found.suggestion
        if (suggestion.latestChapter != null || suggestion.chapterCount != null) return
        row.scope.launchIO {
            val peeked = interactiveSearches.withPermit {
                runCatchingCancellable { adapter.peekCounts(suggestion) }.getOrNull()
            } ?: return@launchIO
            row.search.compareAndSet(found, SearchPhase.Found(peeked, found.sourceName))
            // An accept may have copied the un-peeked suggestion into chosen meanwhile; keep both in
            // step. CAS, so an un-accept landing in between is not resurrected.
            if (MigrationRowRules.canChoose(row.commit.value)) {
                row.chosen.compareAndSet(suggestion, peeked)
            }
            // Hide-without-updates compares these counts, so visibility can change with them.
            syncCounts()
        }
    }

    /** One row against every configured source, either ranked by chapter count or first-hit-wins. */
    private suspend fun search(
        row: MigratingEntryRow,
        sources: List<MigrationSourceUi>,
        tuning: MigrationTuning,
    ): SearchPhase {
        var errors = 0
        val hit: Pair<MigrationSourceUi, MigrationCandidate>? = if (
            tuning.prioritizeByChapters && adapter.supportsSmartMatch
        ) {
            val permits = Semaphore(SOURCE_CONCURRENCY)
            val probes = sources.map { source ->
                row.scope.async {
                    source to permits.withPermit {
                        runCatchingCancellable { adapter.suggest(row.entry, source.key, tuning) }
                    }
                }
            }.awaitAll()
            errors = probes.count { (_, result) -> result.isFailure }
            probes.mapNotNull { (source, result) ->
                // A zero-chapter target is never the better match, matching upstream's ranking.
                result.getOrNull()?.takeIf { (it.chapterCount ?: 0) > 0 }?.let { source to it }
            }.maxByOrNull { (_, candidate) -> candidate.latestChapter ?: 0.0 }
        } else {
            var found: Pair<MigrationSourceUi, MigrationCandidate>? = null
            for (source in sources) {
                val result = runCatchingCancellable { adapter.suggest(row.entry, source.key, tuning) }
                if (result.isFailure) {
                    errors++
                    continue
                }
                val candidate = result.getOrNull() ?: continue
                found = source to candidate
                break
            }
            found
        }
        return when {
            hit != null -> SearchPhase.Found(hit.second, hit.first.name)
            // Only call it a failure when nothing answered: a mix of errors and empty results is
            // still a real "no match" for the sources that did answer.
            sources.isNotEmpty() && errors == sources.size -> SearchPhase.Failed
            else -> SearchPhase.NoMatch
        }
    }

    /** The saved target order resolved against the enabled set. The fallback for an empty saved
     *  selection is pinned-only, mirroring what the config screen seeds as Selected, so what that
     *  screen showed is what gets searched; everything enabled only when nothing is pinned either.
     *  An explicit deselect-all also persists an empty list and reads as unset BY DESIGN: the config
     *  screen hides Continue on an empty selection, so a list can never be reached under one. */
    private fun sourcesFor(): List<MigrationSourceUi> {
        val enabled = adapter.enabledSources()
        val byKey = enabled.associateBy { it.key }
        return adapter.savedSelection().mapNotNull { byKey[it] }.ifEmpty {
            val pinned = adapter.pinnedKeys()
            enabled.filter { it.key in pinned }.ifEmpty { enabled }
        }
    }

    /**
     * Recompute the list-level counts into state.
     *
     * The rows own their state in flows the list items collect individually, which keeps a row's
     * update from recomposing the whole list. Nothing about that reaches the screen-level state on
     * its own, so every action that changes what the toolbar and commit bar report ends by calling
     * this.
     */
    private fun syncCounts() = mutableState.update { state ->
        state.copy(
            visibleRows = state.rows.filter { it.isVisibleUnder(state.tuning) },
            searchedCount = state.rows.count { it.isSettled },
            allSearched = state.rows.isNotEmpty() && state.rows.all { it.isSettled },
            committableCount = state.rows.count {
                MigrationRowRules.isCommittable(it.chosen.value, it.commit.value, it.skipped.value)
            },
            untouchedCount = state.rows.count {
                !it.commit.value.isDone && (it.skipped.value || it.chosen.value == null)
            },
            hasUnaccepted = state.rows.any {
                it.chosen.value == null && !it.skipped.value && it.search.value.suggestion != null
            },
            singleCommitInFlight = state.rows.any { it.commit.value.isBusy },
        )
    }

    /**
     * Save edited search options and, when they change what a search would return, run the batch
     * again under them. The hide toggles are pure filters over results already in hand, so they
     * never re-hit the network.
     *
     * A re-run replaces each unfinished row with a fresh object and cancels the old one's scope.
     * Any work still running against a replaced row writes to an object no longer in the list, so
     * abandoned searches need no epoch counter to be told apart from live ones.
     *
     * Returns false when a commit is in flight, since rebuilding rows underneath one would blank
     * what it is migrating.
     */
    fun applyTuning(tuning: MigrationTuning): Boolean {
        if (state.value.isCommitting || state.value.singleCommitInFlight) return false
        val previous = state.value.tuning
        adapter.persistTuning(tuning)
        mutableState.update { it.copy(tuning = tuning) }
        if (!tuning.affectsSearch(previous)) {
            syncCounts()
            return true
        }
        searchJob?.cancel()
        // A re-search is a new list. Holding on to the old batch's rows would leave the finish gate
        // waiting on row objects that no longer exist and can never resolve.
        batchTargets = emptyList()
        val rebuilt = rows.map { row ->
            when (MigrationRowRules.onSearchRestart(row.commit.value)) {
                // A migrated row's result is history, not a suggestion: re-searching it would blank
                // what it migrated onto.
                MigrationRowRules.RestartOutcome.Keep -> row
                MigrationRowRules.RestartOutcome.Requeue -> {
                    row.scope.cancel()
                    // Skip is a user decision, not a search result: it survives the restart (and
                    // keeps the fresh row out of the driver), unlike the discarded accept.
                    MigratingEntryRow(row.entry, screenModelScope.coroutineContext).also {
                        it.skipped.value = row.skipped.value
                    }
                }
            }
        }
        mutableState.update { it.copy(rows = rebuilt) }
        syncCounts()
        startDriver()
        return true
    }

    /** Open or close a row's override picker. Opening runs the first search for it, so the picker
     *  never opens onto an empty panel the user has to prod. */
    fun toggleExpanded(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        val expanding = !row.expanded.value
        row.expanded.value = expanding
        if (expanding && row.overrides.value == MigratingEntryRow.OverrideState.Idle) {
            searchOverrides(id, row.entry.title)
        }
        // Expansion is part of visibility (an open row is never filtered away), so collapsing has to
        // recompute it. Without this the row lingers and then vanishes at some unrelated later moment.
        syncCounts()
    }

    /**
     * Search every configured source for [query] and fill the row's override strips.
     *
     * These run off the batch driver on their own bound, so opening a row answers immediately
     * instead of queueing behind the batch. A re-search cancels its predecessor, and the write
     * checks it is still the row's current search, since cancellation cannot stop a coroutine that
     * has already left its last suspension point.
     */
    fun searchOverrides(id: EntryId, query: String) {
        if (query.isBlank()) return
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        val sources = sourcesFor()
        val fullQuery = listOfNotNull(
            query.trim(),
            state.value.tuning.extraQuery?.takeIf { it.isNotBlank() },
        ).joinToString(" ")

        row.overrideJob?.cancel()
        row.overrides.value = MigratingEntryRow.OverrideState.Loading
        row.overrideJob = row.scope.launch {
            val myJob = coroutineContext[Job]
            val strips = coroutineScope {
                sources.map { source ->
                    async {
                        val result = interactiveSearches.withPermit {
                            runCatchingCancellable { adapter.candidates(row.entry, fullQuery, source.key) }
                        }
                        MigratingEntryRow.OverrideStrip(
                            sourceKey = source.key,
                            sourceName = source.name,
                            sourceLang = source.lang,
                            candidates = result.getOrDefault(emptyList()),
                            error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                        )
                    }
                }.awaitAll()
            }
            if (row.overrideJob === myJob) {
                row.overrides.value = MigratingEntryRow.OverrideState.Loaded(strips)
            }
        }
    }

    /**
     * Apply a target picked on a pushed browse screen, if one came back for a row here.
     *
     * Called when the list returns to the foreground. The pick is a stored row already, so it wraps
     * into a resolved candidate with no search; the same guards as any other pick apply, so a pick
     * that arrives after the row migrated is dropped rather than re-arming it.
     */
    fun collectPendingPick() {
        val snapshot = rows
        screenModelScope.launchIO {
            snapshot.forEach { row ->
                val targetRawId = pickHandoff.take(row.entry.id) ?: return@forEach
                // The entry's own row is never a target: the engines would no-op and the row would
                // read as migrated with nothing done.
                if (targetRawId == row.entry.id.rawId) return@forEach
                val candidate = runCatchingCancellable { adapter.storedCandidate(targetRawId) }.getOrNull()
                    ?: return@forEach
                // A tuning re-run may have replaced the row objects while storedCandidate was in
                // flight; the pick was already consumed, so it lands on the LIVE row for the entry,
                // not the snapshot it started from.
                val live = rows.firstOrNull { it.entry.id == row.entry.id } ?: return@forEach
                if (!MigrationRowRules.canChoose(live.commit.value)) return@forEach
                live.chosen.value = candidate
                live.expanded.value = false
                if (live.skipped.value) {
                    live.skipped.value = false
                    // Un-skipping a row the driver never reached must hand it back, like toggleSkip
                    // does, or it sits Queued forever and allSearched never turns true.
                    if (live.search.value is SearchPhase.Queued) startDriver()
                }
                syncCounts()
            }
        }
    }

    /** Display name for a candidate's source, for the row status line. */
    fun sourceDisplayName(sourceKey: String): String = adapter.sourceDisplayName(sourceKey)

    /**
     * Fill the chosen target's chapter counts in the background, so the count line stops reading
     * unknown once a target is accepted. Display-only and best-effort; runs in the row's own scope,
     * and a result whose row moved on (target swapped, commit started) is dropped, not applied.
     */
    private fun peekChosenCounts(row: MigratingEntryRow) {
        val candidate = row.chosen.value ?: return
        if (candidate.latestChapter != null || candidate.chapterCount != null) return
        row.scope.launchIO {
            val peeked = interactiveSearches.withPermit {
                runCatchingCancellable { adapter.peekCounts(candidate) }.getOrNull()
            } ?: return@launchIO
            if (!MigrationRowRules.canChoose(row.commit.value)) return@launchIO
            // CAS, not check-then-write: an un-accept landing between them would be resurrected by
            // a plain write, re-arming a target the user just declined.
            row.chosen.compareAndSet(candidate, peeked)
        }
    }

    /** Accept a specific candidate from an override strip, in place of the suggestion. */
    fun pick(id: EntryId, candidate: MigrationCandidate) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (!MigrationRowRules.canChoose(row.commit.value)) return
        row.chosen.value = candidate
        // A pick answers the question the picker was open for; leaving it open buries the result.
        row.expanded.value = false
        row.skipped.value = false
        // Un-skipping a row the driver never reached puts the driver back to work, or it sits Queued
        // forever and allSearched never turns true.
        if (row.search.value is SearchPhase.Queued) startDriver()
        peekChosenCounts(row)
        syncCounts()
    }

    /** Accept the suggestion, or give back an accepted target so the suggestion shows again. */
    fun toggleAccept(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (row.chosen.value != null) {
            if (!MigrationRowRules.canUnchoose(row.commit.value)) return
            row.chosen.value = null
            // Giving a target back must not make the row disappear. Pinning rather than testing
            // visibility here, because the filter's inputs can still arrive later: a row whose
            // search has not settled looks visible now and is hidden the moment NoMatch lands.
            row.pinnedVisible.value = true
        } else {
            if (!MigrationRowRules.canChoose(row.commit.value)) return
            row.chosen.value = row.search.value.suggestion ?: return
            peekChosenCounts(row)
        }
        syncCounts()
    }

    /** Accept every row that found a match and has no target yet. */
    fun acceptAll() {
        if (state.value.isCommitting || state.value.singleCommitInFlight) return
        rows.forEach { row ->
            if (row.chosen.value != null || row.skipped.value) return@forEach
            if (!MigrationRowRules.canChoose(row.commit.value)) return@forEach
            row.chosen.value = row.search.value.suggestion ?: return@forEach
            peekChosenCounts(row)
        }
        syncCounts()
    }

    /**
     * Skip a row out of the migration, or restore it. A skipped row keeps its accepted target and
     * stays in place, so restoring puts it back exactly as it was, and it stops counting toward the
     * commit gate, which is what makes skip the way out of a source that will not answer.
     */
    fun toggleSkip(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (!MigrationRowRules.canToggleSkip(row.commit.value)) return
        val skipping = !row.skipped.value
        row.skipped.value = skipping
        when {
            // Skipping the row being searched abandons that search now, rather than after whatever
            // the source is doing finally returns.
            skipping && row.search.value is SearchPhase.Searching -> row.scope.coroutineContext.cancelChildren()
            // Restoring a row that never searched puts the driver back to work.
            !skipping && row.search.value is SearchPhase.Queued -> startDriver()
        }
        syncCounts()
        // Skipping a failed row is giving up on it; if it was the last thing holding the screen
        // open, the batch is done. The gate decides that, not this call site.
        if (skipping && row.commit.value is CommitPhase.Failed) finishIfNothingFailed()
    }

    fun commit(replace: Boolean, flags: Set<MigrationDataFlag>) {
        // The batch serializes with single commits the same way they serialize with it: two paths
        // committing at once would race on the rows and the flag preference.
        if (state.value.isCommitting || state.value.singleCommitInFlight) return
        val targets = rows.filter {
            MigrationRowRules.isCommittable(it.chosen.value, it.commit.value, it.skipped.value)
        }
        if (targets.isEmpty()) return
        // Persisted once, here, and then carried as a value: the per-row commits below must all use
        // the set the user just confirmed.
        adapter.persistFlags(flags)
        mutableState.update { it.copy(savedFlags = flags) }
        mutableState.update { it.copy(dialog = Dialog.Progress(0, targets.size), isCommitting = true) }
        batchTargets = targets
        commitJob = screenModelScope.launchIO {
            try {
                targets.forEachIndexed { index, row ->
                    ensureActive()
                    commitRow(row, replace, flags)
                    mutableState.update { it.copy(dialog = Dialog.Progress(index + 1, targets.size)) }
                }
                // Only a clean run leaves: a failure keeps the screen open so the row that failed
                // stays visible with its retry, instead of vanishing with the rest.
                finishIfNothingFailed()
            } finally {
                mutableState.update { it.copy(dialog = null, isCommitting = false) }
                commitJob = null
            }
        }
    }

    /** Commit one row now with the saved flags, the per-row Migrate / Copy action. */
    fun commitSingle(id: EntryId, replace: Boolean) {
        // Single commits serialize with each other and with the batch: two at once would race on
        // the same rows and on the flag preference.
        if (state.value.isCommitting || state.value.singleCommitInFlight) return
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (!MigrationRowRules.isCommittable(row.chosen.value, row.commit.value, row.skipped.value)) return
        val flags = state.value.savedFlags
        adapter.persistFlags(flags)
        screenModelScope.launchIO {
            commitRow(row, replace, flags)
            finishIfNothingFailed()
        }
    }

    fun retry(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        val failure = row.commit.value as? CommitPhase.Failed ?: return
        val busy = state.value.isCommitting || state.value.singleCommitInFlight
        if (!MigrationRowRules.canRetry(failure, busy, row.skipped.value)) return
        screenModelScope.launchIO {
            commitRow(row, failure.replace, failure.flags)
            finishIfNothingFailed()
        }
    }

    /**
     * Called unconditionally by every path that can resolve the last outstanding row: a commit
     * finishing, a retry, a skip. EVERY condition lives here rather than at the call sites, because
     * guarding it per caller is how the screen has both popped early and failed to pop at all: one
     * predicate cannot disagree with itself. The screen finishes only when a batch ran and every row
     * it was responsible for is now resolved (migrated or skipped), something actually migrated,
     * every row is settled (a pop mid-search would abandon rows still being searched), no NON-SKIPPED
     * row is failed (skipping a failed row is giving up on it, which should not hold the screen open
     * forever), nothing is still committing (finishing would cancel it half-applied), and nothing
     * committable remains (a cancelled batch leaves accepted rows behind; finishing over them would
     * silently drop their migrations).
     */
    private fun finishIfNothingFailed() {
        val batch = batchTargets
        if (batch.isEmpty() || state.value.migratedCount == 0) return
        if (batch.any { !it.commit.value.isDone && !it.skipped.value }) return
        if (rows.any { !it.isSettled }) return
        val anyFailed = rows.any { it.commit.value is CommitPhase.Failed && !it.skipped.value }
        val anyBusy = rows.any { it.commit.value.isBusy }
        val anyCommittable = rows.any {
            MigrationRowRules.isCommittable(it.chosen.value, it.commit.value, it.skipped.value)
        }
        if (!anyFailed && !anyBusy && !anyCommittable) {
            batchTargets = emptyList()
            mutableState.update { it.copy(finished = true) }
        }
    }

    /**
     * The one commit path: resolve, migrate, record the outcome on the row. Resolving here rather
     * than at accept keeps a bulk accept free and is a no-op for an already-resolved candidate; a
     * cancelled commit is marked failed because the engines are not transactional and the row may
     * be half-applied. When the resolve did fetch the target, the engine is told to skip its own
     * identical fetch, which halves what a batch asks of the target source.
     */
    private suspend fun commitRow(
        row: MigratingEntryRow,
        replace: Boolean,
        flags: Set<MigrationDataFlag>,
    ) {
        // Claim the row atomically: the busy mark used to land only once this coroutine ran, so a
        // double-tap on Retry (or a batch racing a single commit) could commit the same row twice.
        // The loser of the claim no-ops.
        val previous = row.commit.value
        if (previous.isBusy || previous.isDone) return
        if (!row.commit.compareAndSet(previous, CommitPhase.Committing(replace))) return
        // The target and skip state are read AFTER the claim: once Committing is visible every
        // chooser and skipper refuses, so a swap or skip that slipped in between the caller's guard
        // and the claim is caught here instead of being migrated and then overwritten.
        val target = row.chosen.value
        if (target == null || row.skipped.value) {
            row.commit.value = previous
            // Releasing the claim can put an unsearched row back in the driver's reach, and the
            // driver may already have run out of work and exited. Every other path back into
            // searchable does this too.
            if (row.search.value is SearchPhase.Queued) startDriver()
            syncCounts()
            return
        }
        syncCounts()
        try {
            val resolved = adapter.commitMigration(row.entry, target, replace, flags)
            // CAS, not a plain write: if anything legitimately changed chosen mid-commit, the user's
            // choice wins over the bookkeeping copy.
            row.chosen.compareAndSet(target, resolved)
            row.commit.value = CommitPhase.Migrated(resolved, replace)
            mutableState.update { it.copy(migratedCount = it.migratedCount + 1) }
        } catch (e: CancellationException) {
            row.commit.value = CommitPhase.Failed(replace, flags)
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Migration failed for ${row.entry.id}" }
            row.commit.value = CommitPhase.Failed(replace, flags)
        } finally {
            syncCounts()
        }
    }

    fun cancelCommit() {
        commitJob?.cancel()
        commitJob = null
    }

    /** Opens immediately and fills the applicable flags behind it, so the button never looks dead
     *  while the per-entry scan (custom cover, notes, downloads) runs. */
    fun showConfirm(replace: Boolean) {
        if (state.value.isCommitting || state.value.singleCommitInFlight) return
        val scan = ++confirmScanId
        mutableState.update {
            it.copy(dialog = Dialog.Confirm(replace, it.committableCount, it.untouchedCount))
        }
        screenModelScope.launchIO {
            val targets = rows.filter {
                MigrationRowRules.isCommittable(it.chosen.value, it.commit.value, it.skipped.value)
            }
            val applicable = adapter.applicableFlags(targets.map { it.entry })
            // The saved set is re-read rather than reused: an earlier commit in this session rewrote
            // the preference, and the seed has to match what the next migration will actually use.
            val saved = adapter.savedFlags()
            mutableState.update {
                val dialog = it.dialog
                if (scan != confirmScanId || dialog !is Dialog.Confirm) return@update it
                it.copy(dialog = dialog.copy(applicableFlags = applicable, savedFlags = saved, loadingFlags = false))
            }
        }
    }

    fun showExitConfirm() = mutableState.update { it.copy(dialog = Dialog.Exit) }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    override fun onDispose() {
        super.onDispose()
        // Row scopes are detached, so they outlive the model unless cancelled here.
        rows.forEach { it.scope.cancel() }
        // An uncollected pick belongs to this migration only.
        pickHandoff.clear()
    }

    sealed interface Dialog {
        data class Confirm(
            val replace: Boolean,
            val count: Int,
            val untouched: Int,
            val applicableFlags: Set<MigrationDataFlag> = emptySet(),
            /** The FULL saved set seeds the checkboxes: only applicable flags render, so a flag that
             *  is hidden for this batch keeps its saved state instead of being cleared. */
            val savedFlags: Set<MigrationDataFlag> = emptySet(),
            val loadingFlags: Boolean = true,
        ) : Dialog
        data class Progress(val done: Int, val total: Int) : Dialog
        data object Exit : Dialog
    }

    /** Counts are stored, not derived: see [syncCounts]. */
    data class State(
        val isLoading: Boolean = true,
        val tuning: MigrationTuning = MigrationTuning(),
        val rows: List<MigratingEntryRow> = emptyList(),
        /** [rows] after the hide toggles; what the list renders. */
        val visibleRows: List<MigratingEntryRow> = emptyList(),
        val searchedCount: Int = 0,
        /** Every row has settled, so the totals the commit bar shows are final. */
        val allSearched: Boolean = false,
        val committableCount: Int = 0,
        /** Rows a commit would leave alone: skipped, or with nothing to migrate onto. */
        val untouchedCount: Int = 0,
        val hasUnaccepted: Boolean = false,
        val singleCommitInFlight: Boolean = false,
        val supportsSmartMatch: Boolean = false,
        val migratedCount: Int = 0,
        val isCommitting: Boolean = false,
        val finished: Boolean = false,
        val savedFlags: Set<MigrationDataFlag> = emptySet(),
        val dialog: Dialog? = null,
    ) {
        val rowIds: Set<EntryId> = rows.mapTo(HashSet()) { it.entry.id }
    }
}

/** Settled for progress purposes; the rule itself lives with the other transition rules. */
private val MigratingEntryRow.isSettled: Boolean
    get() = MigrationRowRules.isSettled(search.value, commit.value, skipped.value)

/**
 * Whether the hide toggles leave this row on screen. An accepted row is always shown: hiding one the
 * user has chosen would commit it invisibly. A failed search is shown too, since hide-unmatched is
 * about entries with no match, not about entries whose sources were unreachable. An expanded row is
 * shown whatever the toggles say: it is the row being worked on, and un-accepting inside it would
 * otherwise make it vanish under the user's hands.
 */
private fun MigratingEntryRow.isVisibleUnder(tuning: MigrationTuning): Boolean = MigrationRowRules.isVisible(
    search = search.value,
    chosen = chosen.value,
    entryLatestChapter = entry.latestChapter,
    pinned = pinnedVisible.value || expanded.value,
    tuning = tuning,
)
