package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import reikai.presentation.migrate.flow.MigratingEntryRow.Acceptance
import reikai.presentation.migrate.flow.MigratingEntryRow.CommitPhase
import reikai.presentation.migrate.flow.MigratingEntryRow.SearchPhase
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
    private val entryIds: List<Long>,
    private val extraQuery: String?,
    // Injected rather than resolved in the initialiser, so the driver, the claim and the finish gate
    // can be constructed in a plain JVM test. Resolving Injekt here made every one of them reachable
    // only by reading the code, which is why each gate defect was found by an audit and never by a
    // test. The screen resolves them and passes them in.
    private val adapter: MigrationFlowAdapter,
    private val pickHandoff: MigrationPickHandoff,
    // Injected so a test can drive the driver and the commits on its own scheduler; production
    // callers take the default, which is what launchIO would have used.
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StateScreenModel<EntryMigrationListScreenModel.State>(State()) {

    private val rows: List<MigratingEntryRow> get() = state.value.rows

    /** Volatile like the other cross-thread fields: it is assigned on the caller's thread and nulled
     *  from the commit coroutine, and a stale read left the progress dialog's Cancel cancelling a
     *  job that had already finished, or nothing at all. */
    @Volatile
    private var commitJob: Job? = null

    /**
     * Whether a batch commit has run on this list. The screen never finishes itself without one, so
     * a lone "Migrate now" cannot close a list the user is still working; a tuning re-search clears
     * it, since that is a new list.
     *
     * This used to hold the batch's row objects and check each one had been resolved. That scan was
     * redundant with the committable and busy checks below (an unresolved batch row is committable
     * or busy by definition) and it was the clause that treated a declined row as unfinished
     * business, wedging the gate for the rest of the session.
     */
    @Volatile
    private var batchRan = false

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
        screenModelScope.launch(io) {
            adapter.prepare()
            val tuning = adapter.readTuning()
                .copy(extraQuery = extraQuery)
                .normalizedFor(adapter.matchStrategy)
            val built = adapter.loadEntries(entryIds).map {
                MigratingEntryRow(it, screenModelScope.coroutineContext, io)
            }
            mutableState.update {
                it.copy(
                    isLoading = false,
                    tuning = tuning,
                    rows = built,
                    matchStrategy = adapter.matchStrategy,
                    hasSources = sourcesFor().isNotEmpty(),
                    savedFlags = adapter.savedFlags(),
                )
            }
            // Seeded through the same function every later change uses, rather than assigning
            // visibleRows here. Setting it by hand left emptyReason unwritten, and a load that
            // produced no rows never reached syncCounts (the driver breaks out before it), so the
            // screen rendered a blank list instead of saying why it was empty.
            syncCounts()
            startDriver()
        }
    }

    /** Run the search driver unless it is already running. Restoring a skipped row calls this too,
     *  since the driver stops once nothing is left to search. Synchronized so two callers on
     *  different threads cannot both pass the liveness check and start twin drivers. */
    @Synchronized
    private fun startDriver() {
        if (searchJob?.isActive == true) return
        searchJob = screenModelScope.launch(io) { drive() }
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
        screenModelScope.launch(io) {
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
        row.scope.launch(io) {
            val peeked = interactiveSearches.withPermit {
                runCatchingCancellable { adapter.peekCounts(suggestion) }.getOrNull()
            } ?: return@launch
            row.search.compareAndSet(found, SearchPhase.Found(peeked, found.sourceName))
            // An accept may have copied the un-peeked suggestion into chosen meanwhile; keep both in
            // step. CAS, so an un-accept landing in between is not resurrected.
            if (MigrationRowRules.canChoose(row.commit.value)) {
                row.acceptance.compareAndSet(Acceptance.Accepted(suggestion), Acceptance.Accepted(peeked))
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
            tuning.prioritizeByChapters && adapter.matchStrategy is MatchStrategy.Smart
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
        val visible = state.rows.filter { it.isVisibleUnder(state.tuning) }
        state.copy(
            visibleRows = visible,
            emptyReason = when {
                state.rows.isNotEmpty() && visible.isEmpty() -> EmptyReason.AllFiltered
                state.rows.isEmpty() && !state.hasSources -> EmptyReason.NoSources
                state.rows.isEmpty() -> EmptyReason.NoEntries
                else -> null
            },
            searchedCount = state.rows.count { it.isSettled },
            allSearched = state.rows.isNotEmpty() && state.rows.all { it.isSettled },
            committableCount = state.rows.count {
                MigrationRowRules.isCommittable(it.acceptance.value, it.commit.value, it.skipped.value)
            },
            untouchedCount = state.rows.count {
                !it.commit.value.isDone && it.disposition != MigrationRowRules.Disposition.Armed
            },
            hasUnaccepted = state.rows.any { it.isUntouched && it.search.value.suggestion != null },
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
    fun applyTuning(edited: MigrationTuning): Boolean {
        if (state.value.isBusy) return false
        // Dropped here, once, rather than relying on the sheet to hide the controls: an option this
        // content type cannot run must not reach the comparison below and buy a rebuild for nothing.
        val tuning = edited.normalizedFor(adapter.matchStrategy)
        val previous = state.value.tuning
        // Off the caller's thread: this is a preference write, and every one of these ran on main.
        screenModelScope.launch(io) { adapter.persistTuning(tuning) }
        mutableState.update { it.copy(tuning = tuning) }
        if (!tuning.affectsSearch(previous)) {
            syncCounts()
            return true
        }
        searchJob?.cancel()
        // A re-search is a new list, so the previous batch is no longer what the gate waits on.
        batchRan = false
        val rebuilt = rows.map { row ->
            when (MigrationRowRules.onSearchRestart(row.commit.value)) {
                // A migrated row's result is history, not a suggestion: re-searching it would blank
                // what it migrated onto.
                MigrationRowRules.RestartOutcome.Keep -> row
                MigrationRowRules.RestartOutcome.Requeue -> {
                    row.scope.cancel()
                    // Skip is a user decision, not a search result: it survives the restart (and
                    // keeps the fresh row out of the driver).
                    MigratingEntryRow(row.entry, screenModelScope.coroutineContext, io).also {
                        it.skipped.value = row.skipped.value
                        // So is a target the user picked by hand. Accepting the suggestion is a
                        // verdict on a result this restart is discarding, so that one goes; a target
                        // chosen from an override strip or the browse picker did not come from the
                        // batch search at all, and a re-search cannot reproduce a browse pick.
                        val chosen = row.acceptance.value as? Acceptance.Accepted
                        if (chosen != null && chosen.candidate != row.search.value.suggestion) {
                            it.acceptance.value = chosen
                        }
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
        // Same permission the screen renders the control under, so the two cannot disagree.
        if (!MigrationRowRules.canChoose(row.commit.value)) return
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
        val fullQuery = listOfNotNull(
            query.trim(),
            state.value.tuning.extraQuery?.takeIf { it.isNotBlank() },
        ).joinToString(" ")

        row.overrideJob?.cancel()
        row.overrides.value = MigratingEntryRow.OverrideState.Preparing
        row.overrideJob = row.scope.launch {
            val myJob = coroutineContext[Job]
            // Resolved here rather than on the caller's thread: it reads three preferences, and this
            // runs on every row expansion and every tap of the picker's search button.
            val sources = sourcesFor()
            if (row.overrideJob !== myJob) return@launch
            // Published BEFORE the searches run, so every source shows itself immediately and fills in
            // when it answers. Waiting for all of them meant one dead source hid the rest.
            row.overrides.value = MigratingEntryRow.OverrideState.Strips(
                sources.map {
                    MigratingEntryRow.OverrideStrip(
                        sourceKey = it.key,
                        sourceName = it.name,
                        sourceLang = it.lang,
                        result = MigratingEntryRow.StripResult.Loading,
                    )
                },
            )
            coroutineScope {
                sources.forEach { source ->
                    launch {
                        val result = interactiveSearches.withPermit {
                            runCatchingCancellable { adapter.candidates(row.entry, fullQuery, source.key) }
                        }
                        // Same guard the single write had: cancellation cannot stop a coroutine that
                        // is already past its last suspension point.
                        if (row.overrideJob !== myJob) return@launch
                        val landed = result.fold(
                            onSuccess = { MigratingEntryRow.StripResult.Loaded(it) },
                            onFailure = {
                                MigratingEntryRow.StripResult.Failed(it.message ?: it.javaClass.simpleName)
                            },
                        )
                        row.overrides.update { current ->
                            val open = current as? MigratingEntryRow.OverrideState.Strips ?: return@update current
                            MigratingEntryRow.OverrideState.Strips(
                                open.strips.map { if (it.sourceKey == source.key) it.copy(result = landed) else it },
                            )
                        }
                    }
                }
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
        screenModelScope.launch(io) {
            snapshot.forEach { row ->
                val targetRawId = pickHandoff.take(row.entry.id) ?: return@forEach
                // The entry's own row is never a target: the engines would no-op and the row would
                // read as migrated with nothing done.
                if (targetRawId == row.entry.id.rawId) {
                    reportPick(PickOutcome.SameEntry)
                    return@forEach
                }
                val candidate = runCatchingCancellable { adapter.storedCandidate(targetRawId) }.getOrNull()
                    ?: run {
                        reportPick(PickOutcome.Unavailable)
                        return@forEach
                    }
                // A tuning re-run may have replaced the row objects while storedCandidate was in
                // flight; the pick was already consumed, so it lands on the LIVE row for the entry,
                // not the snapshot it started from.
                val live = rows.firstOrNull { it.entry.id == row.entry.id } ?: return@forEach
                if (!MigrationRowRules.canChoose(live.commit.value)) return@forEach
                live.acceptance.value = Acceptance.Accepted(candidate)
                // Same follow-up every other accept path runs: a browsed candidate carries no counts
                // until something asks, and without this the row read unknown forever and the
                // chapter-shortfall warning could never fire on a deep pick.
                peekChosenCounts(live)
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

    private fun reportPick(outcome: PickOutcome) = mutableState.update { it.copy(pickOutcome = outcome) }

    /** Called once the screen has shown the outcome; see [PickOutcome]. */
    fun consumePickOutcome() = mutableState.update { it.copy(pickOutcome = null) }

    /** Display name for a candidate's source, for the row status line. */
    fun sourceDisplayName(sourceKey: String): String = adapter.sourceDisplayName(sourceKey)

    /**
     * Fill the chosen target's chapter counts in the background, so the count line stops reading
     * unknown once a target is accepted. Display-only and best-effort; runs in the row's own scope,
     * and a result whose row moved on (target swapped, commit started) is dropped, not applied.
     */
    private fun peekChosenCounts(row: MigratingEntryRow) {
        val candidate = row.acceptance.value.candidate ?: return
        if (candidate.latestChapter != null || candidate.chapterCount != null) return
        row.scope.launch(io) {
            val peeked = interactiveSearches.withPermit {
                runCatchingCancellable { adapter.peekCounts(candidate) }.getOrNull()
            } ?: return@launch
            if (!MigrationRowRules.canChoose(row.commit.value)) return@launch
            // CAS, not check-then-write: an un-accept landing between them would be resurrected by
            // a plain write, re-arming a target the user just declined.
            row.acceptance.compareAndSet(Acceptance.Accepted(candidate), Acceptance.Accepted(peeked))
        }
    }

    /** Accept a specific candidate from an override strip, in place of the suggestion. */
    fun pick(id: EntryId, candidate: MigrationCandidate) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (!MigrationRowRules.canChoose(row.commit.value)) return
        row.acceptance.value = Acceptance.Accepted(candidate)
        // A pick answers the question the picker was open for; leaving it open buries the result.
        row.expanded.value = false
        row.skipped.value = false
        // Un-skipping a row the driver never reached puts the driver back to work, or it sits Queued
        // forever and allSearched never turns true.
        if (row.search.value is SearchPhase.Queued) startDriver()
        peekChosenCounts(row)
        syncCounts()
    }

    /**
     * Accept the suggestion, or give back an accepted target so the suggestion shows again.
     *
     * Both branches read the same [MigrationRowRules.actions] the screen renders the control under, so
     * neither can drift from it. Checking a hand-picked subset of the rules here is how this pair went
     * wrong: both branches ignored `skipped` while the screen's accept control honoured it, so the
     * un-accept control rendered on a skipped row and threw away the target skip exists to preserve.
     */
    fun toggleAccept(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        val actions = MigrationRowRules.actions(
            row.search.value,
            row.acceptance.value,
            row.commit.value,
            row.skipped.value,
            anyCommitInFlight = state.value.isBusy,
        )
        if (row.acceptance.value is Acceptance.Accepted) {
            if (!actions.canUnaccept) return
            // Declined, not null: the hide toggles must be able to tell a target the user gave
            // back from one they never had, or the row vanishes from under them.
            row.acceptance.value = Acceptance.Declined
            syncCounts()
            // Declining settles the row, so it can be the last thing a batch was waiting on. The
            // gate decides whether that finishes the screen, not this call site.
            finishIfNothingFailed()
            return
        } else {
            if (!actions.canAccept) return
            row.acceptance.value = Acceptance.Accepted(row.search.value.suggestion ?: return)
            peekChosenCounts(row)
        }
        syncCounts()
    }

    /** Accept every row that found a match and the user has not acted on yet. */
    fun acceptAll() {
        if (state.value.isBusy) return
        rows.forEach { row ->
            // Untouched only: a declined row is a decision, and re-arming it here would migrate a
            // target the user just handed back.
            if (!row.isUntouched) return@forEach
            if (!MigrationRowRules.canChoose(row.commit.value)) return@forEach
            row.acceptance.value = Acceptance.Accepted(row.search.value.suggestion ?: return@forEach)
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
            skipping && row.search.value is SearchPhase.Searching -> {
                row.scope.coroutineContext.cancelChildren()
                // That takes the row's manual picker down with it, mid-stream, so any strip still
                // waiting would sit on a spinner nothing ever clears. Back to Idle unconditionally,
                // which is the state a re-expand searches from; keeping half a result set would be
                // presenting a cancelled search as a finished one.
                row.overrides.value = MigratingEntryRow.OverrideState.Idle
            }
            // Restoring a row that never searched puts the driver back to work.
            !skipping && row.search.value is SearchPhase.Queued -> startDriver()
        }
        syncCounts()
        // Skipping settles the row, whatever state it was in; if it was the last thing holding the
        // screen open, the batch is done. The gate decides that, not this call site.
        if (skipping) finishIfNothingFailed()
    }

    fun commit(replace: Boolean, flags: Set<MigrationDataFlag>) {
        // The batch serializes with single commits the same way they serialize with it: two paths
        // committing at once would race on the rows and the flag preference.
        if (state.value.isBusy) return
        val targets = rows.filter {
            MigrationRowRules.isCommittable(it.acceptance.value, it.commit.value, it.skipped.value)
        }
        if (targets.isEmpty()) return
        // The confirm request is consumed by the batch it started, in the same write that starts it,
        // so it cannot come back when the batch ends on a partial failure.
        mutableState.update {
            it.copy(
                savedFlags = flags,
                dialog = null,
                commit = CommitActivity.Batch(done = 0, total = targets.size),
            )
        }
        batchRan = true
        commitJob = screenModelScope.launch(io) {
            // Persisted once, and then carried as a value: the per-row commits below all use the set
            // the user just confirmed, so this write only seeds the NEXT migration and belongs here
            // rather than on the caller's thread.
            adapter.persistFlags(flags)
            try {
                targets.forEachIndexed { index, row ->
                    ensureActive()
                    commitRow(row, replace, flags)
                    mutableState.update { it.copy(commit = CommitActivity.Batch(index + 1, targets.size)) }
                }
                // Only a clean run leaves: a failure keeps the screen open so the row that failed
                // stays visible with its retry, instead of vanishing with the rest.
                finishIfNothingFailed()
            } finally {
                mutableState.update { it.copy(commit = CommitActivity.Idle) }
                commitJob = null
            }
        }
    }

    /** Commit one row now with the saved flags, the per-row Migrate / Copy action. */
    fun commitSingle(id: EntryId, replace: Boolean) {
        // Single commits serialize with each other and with the batch: two at once would race on
        // the same rows and on the flag preference.
        if (state.value.isBusy) return
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (!MigrationRowRules.isCommittable(row.acceptance.value, row.commit.value, row.skipped.value)) return
        runSingleCommit(row, replace, state.value.savedFlags)
    }

    fun retry(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        val failure = row.commit.value as? CommitPhase.Failed ?: return
        if (!MigrationRowRules.canRetry(failure, state.value.isBusy, row.skipped.value)) return
        runSingleCommit(row, failure.replace, failure.flags)
    }

    /**
     * Commit one row, marking the screen busy on the CALLER's thread.
     *
     * The mark used to land inside the coroutine, which left a dispatch-sized window where a second
     * commit, or a tuning rebuild, passed its guard against a commit that had already been decided:
     * the rebuild swapped in a fresh row while the commit migrated the old object, leaving the entry
     * migrated in the database and shown as untouched.
     */
    private fun runSingleCommit(row: MigratingEntryRow, replace: Boolean, flags: Set<MigrationDataFlag>) {
        mutableState.update { it.copy(commit = CommitActivity.Single(row.entry.id)) }
        // Recorded like the batch's, so [cancelCommit] reaches BOTH commit shapes. It only ever held
        // the batch job, which made the exit dialog's cancel a no-op on the per-row path: Stop fell
        // through to the pop, and the migration was cancelled by the scope teardown mid-write instead.
        commitJob = screenModelScope.launch(io) {
            // No persistFlags here: only a confirm may move the preference. A retry re-runs with the
            // exact set its row failed under, which is older than whatever the user has confirmed
            // since, so writing it back reverted a choice they had already made.
            try {
                commitRow(row, replace, flags)
                finishIfNothingFailed()
            } finally {
                mutableState.update { it.copy(commit = CommitActivity.Idle) }
                commitJob = null
            }
        }
    }

    /**
     * Called unconditionally by every path that can settle the last outstanding row: a commit
     * finishing, a retry, a skip, a decline. EVERY condition lives here rather than at the call
     * sites, because guarding it per caller is how the screen has both popped early and failed to
     * pop at all: one predicate cannot disagree with itself. The screen finishes only when a batch
     * ran, something actually migrated, every row is settled (a pop mid-search would abandon rows
     * still being searched), nothing is still committing (finishing would cancel it half-applied),
     * and nothing committable remains (a cancelled batch leaves accepted rows behind; finishing over
     * them would silently drop their migrations).
     *
     * That last check carries more than it looks. A failed row is committable by definition (it kept
     * its target, and un-accepting is refused while a commit is in play), which is what holds the
     * screen open on a partial failure; skipping a failed row is giving up on it, and drops it out of
     * the same check. A row the batch never reached is committable or busy too, so the gate needs no
     * separate per-batch scan. A stated failure test used to sit beside this and pinned nothing.
     */
    private fun finishIfNothingFailed() {
        if (!batchRan || state.value.migratedCount == 0) return
        if (rows.any { !it.isSettled }) return
        val anyBusy = rows.any { it.commit.value.isBusy }
        val anyCommittable = rows.any {
            MigrationRowRules.isCommittable(it.acceptance.value, it.commit.value, it.skipped.value)
        }
        if (!anyBusy && !anyCommittable) {
            batchRan = false
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
        val target = row.acceptance.value.candidate
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
            row.acceptance.compareAndSet(Acceptance.Accepted(target), Acceptance.Accepted(resolved))
            // A skip that landed between this commit's claim and its read of the flag lost the race:
            // the migration has already happened. Clearing the flag as the row goes terminal is what
            // makes "migrated AND skipped" unrepresentable, rather than leaving a row dimmed as
            // skipped, showing a migration that did happen, with skip refused from here on.
            row.skipped.value = false
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
        if (state.value.isBusy) return
        val scan = ++confirmScanId
        mutableState.update {
            it.copy(dialog = Dialog.Confirm(replace, it.committableCount, it.untouchedCount))
        }
        screenModelScope.launch(io) {
            val targets = rows.filter {
                MigrationRowRules.isCommittable(it.acceptance.value, it.commit.value, it.skipped.value)
            }
            val applicable = adapter.applicableFlags(targets.map { it.entry })
            // The saved set is re-read rather than reused: an earlier commit in this session rewrote
            // the preference, and the seed has to match what the next migration will actually use.
            val saved = adapter.savedFlags()
            mutableState.update {
                val open = it.dialog
                if (scan != confirmScanId || open !is Dialog.Confirm) return@update it
                it.copy(dialog = open.copy(applicableFlags = applicable, savedFlags = saved, loadingFlags = false))
            }
        }
    }

    /** Ask before leaving. Allowed mid-commit, and it cannot touch the commit cell: back used to
     *  overwrite the one cell that held both, which cleared the busy state under a running migration. */
    fun showExitConfirm() = mutableState.update { it.copy(dialog = Dialog.ExitConfirm) }

    /** Close an open dialog. Only a dialog: a commit is not something a dismissal may cancel. */
    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    override fun onDispose() {
        super.onDispose()
        // Row scopes are detached, so they outlive the model unless cancelled here.
        rows.forEach { it.scope.cancel() }
        // An uncollected pick belongs to this migration only.
        pickHandoff.clear()
    }

    /**
     * What is actually running. The truth about the commit, and nothing else.
     *
     * A dialog request is deliberately NOT in here. It was, and the exit-confirm writer overwrote
     * this cell unconditionally, so pressing back during a per-row commit cleared the busy state
     * while the migration ran on: the commit bar came back live, and Stop unwound the flow mid-write
     * with nothing said to the user. A modal is something the user asked to see; a commit is
     * something the app is doing. Two facts, so two cells, with the render rules in [State] deciding
     * what is shown rather than one writer clobbering the other.
     */
    sealed interface CommitActivity {
        data object Idle : CommitActivity

        /** Carries its own progress, so "running" and "how far" cannot disagree. */
        data class Batch(val done: Int, val total: Int) : CommitActivity

        /** A per-row commit: busy, but no modal (the row shows its own spinner). */
        data class Single(val entryId: EntryId) : CommitActivity
    }

    /**
     * A modal the user asked for. Held apart from [CommitActivity] and shown through
     * [State.visibleDialog], which is what stops a confirm dialog outliving the commit it started
     * and sitting on top of the progress dialog with Cancel stranded behind it.
     */
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

        data object ExitConfirm : Dialog
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
        /** False when the type has no source to migrate onto; separates two blank screens. */
        val hasSources: Boolean = true,
        /** Why the list is empty, so the screen explains itself instead of rendering nothing. */
        val emptyReason: EmptyReason? = null,
        val matchStrategy: MatchStrategy = MatchStrategy.BestTitleMatch,
        val migratedCount: Int = 0,
        /** What the app is doing; see [CommitActivity]. */
        val commit: CommitActivity = CommitActivity.Idle,
        /** What the user asked to see; see [Dialog]. */
        val dialog: Dialog? = null,
        val finished: Boolean = false,
        val savedFlags: Set<MigrationDataFlag> = emptySet(),
        /** Consume-once: see [PickOutcome]. */
        val pickOutcome: PickOutcome? = null,
    ) {
        /** No commit may start, and nothing may rebuild rows, while another one is running. An open
         *  dialog is not busy: the user is still deciding. */
        val isBusy: Boolean get() = commit != CommitActivity.Idle

        /**
         * Which modal is on screen, decided here rather than by whichever writer went last. At most
         * one of these two is ever non-null.
         *
         * A running batch owns the screen, so its progress window wins over a confirm dialog that is
         * now answered. The exit confirm outranks even that: back has to stay answerable during a
         * commit, and refusing it would leave no way out of a source that never returns.
         */
        val visibleProgress: CommitActivity.Batch?
            get() = (commit as? CommitActivity.Batch)?.takeIf { dialog !is Dialog.ExitConfirm }

        val visibleDialog: Dialog?
            get() = dialog?.takeIf { it is Dialog.ExitConfirm || commit == CommitActivity.Idle }
    }

    /** Why a migration list has nothing to show. */
    enum class EmptyReason { NoEntries, AllFiltered, NoSources }
}

/** Settled for progress purposes; the rule itself lives with the other transition rules. */
private val MigratingEntryRow.isSettled: Boolean
    get() = MigrationRowRules.isSettled(search.value, commit.value, skipped.value)

/** Where the user stands on this row; the rule itself lives with the other rules. */
private val MigratingEntryRow.disposition: MigrationRowRules.Disposition
    get() = MigrationRowRules.disposition(acceptance.value, commit.value, skipped.value)

private val MigratingEntryRow.isUntouched: Boolean
    get() = disposition == MigrationRowRules.Disposition.Untouched

/**
 * Whether the hide toggles leave this row on screen; the rule itself lives with the other rules.
 */
private fun MigratingEntryRow.isVisibleUnder(tuning: MigrationTuning): Boolean = MigrationRowRules.isVisible(
    search = search.value,
    acceptance = acceptance.value,
    commit = commit.value,
    skipped = skipped.value,
    entryLatestChapter = entry.latestChapter,
    expanded = expanded.value,
    tuning = tuning,
)
