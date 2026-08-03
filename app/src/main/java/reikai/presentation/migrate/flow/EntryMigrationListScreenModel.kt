package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    private val rows: List<MigratingEntryRow> get() = state.value.rows

    private var commitJob: Job? = null

    init {
        screenModelScope.launchIO {
            adapter.prepare()
            val tuning = adapter.readTuning().copy(extraQuery = extraQuery)
            val built = adapter.loadEntries(entryIds).map {
                MigratingEntryRow(entry = it, parentContext = screenModelScope.coroutineContext)
            }
            mutableState.update {
                it.copy(isLoading = false, tuning = tuning, rows = built, savedFlags = adapter.savedFlags())
            }
            runSearches(built, tuning)
        }
    }

    private suspend fun runSearches(batch: List<MigratingEntryRow>, tuning: MigrationTuning) {
        val sources = sourcesFor()
        for (row in batch) {
            if (!currentCoroutineContext().isActive) break
            if (row.entry.id !in state.value.rowIds) continue
            if (!MigrationRowRules.canSearch(row.search.value, row.commit.value, row.skipped.value)) continue
            if (!row.scope.isActive) continue

            row.search.value = SearchPhase.Searching
            val outcome = try {
                row.scope.async { search(row, sources, tuning) }.await()
            } catch (_: CancellationException) {
                // The row was abandoned (skipped, or its generation replaced); the batch carries on.
                continue
            }
            row.search.value = outcome
            // Upstream migrates whatever it found; an explicit accept step lands with its own UI.
            row.chosen.value = outcome.suggestion
            bumpProgress()
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

    /** The saved target order resolved against the enabled set; pinned sources lead when nothing
     *  saved resolves (every saved source disabled or uninstalled). */
    private fun sourcesFor(): List<MigrationSourceUi> {
        val enabled = adapter.enabledSources()
        val byKey = enabled.associateBy { it.key }
        return adapter.savedSelection().mapNotNull { byKey[it] }.ifEmpty {
            val pinned = adapter.pinnedKeys()
            enabled.sortedBy { it.key !in pinned }
        }
    }

    private fun bumpProgress() = mutableState.update { it.copy(searchedCount = it.rows.count { row -> row.isSettled }) }

    fun toggleSkip(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (!MigrationRowRules.canToggleSkip(row.commit.value)) return
        val skipping = !row.skipped.value
        row.skipped.value = skipping
        // Skipping the row being searched abandons that search immediately; restoring an unsearched
        // row re-queues it for a follow-up pass.
        if (skipping && row.search.value is SearchPhase.Searching) {
            row.scope.coroutineContext.cancelChildren()
        }
        bumpProgress()
    }

    fun commit(replace: Boolean) {
        if (state.value.isCommitting) return
        val targets = rows.filter {
            MigrationRowRules.isCommittable(it.chosen.value, it.commit.value, it.skipped.value)
        }
        if (targets.isEmpty()) return
        val flags = state.value.savedFlags
        adapter.persistFlags(flags)
        mutableState.update { it.copy(dialog = Dialog.Progress(0, targets.size), isCommitting = true) }
        commitJob = screenModelScope.launchIO {
            try {
                targets.forEachIndexed { index, row ->
                    ensureActive()
                    commitRow(row, replace, flags, fromBatch = true)
                    mutableState.update { it.copy(dialog = Dialog.Progress(index + 1, targets.size)) }
                }
                mutableState.update { it.copy(finished = true) }
            } finally {
                mutableState.update { it.copy(dialog = null, isCommitting = false) }
                commitJob = null
            }
        }
    }

    /** Commit one row now with the saved flags, the per-row Migrate / Copy action. */
    fun commitSingle(id: EntryId, replace: Boolean) {
        if (state.value.isCommitting) return
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        if (!MigrationRowRules.isCommittable(row.chosen.value, row.commit.value, row.skipped.value)) return
        val flags = state.value.savedFlags
        adapter.persistFlags(flags)
        screenModelScope.launchIO { commitRow(row, replace, flags, fromBatch = false) }
    }

    fun retry(id: EntryId) {
        val row = rows.firstOrNull { it.entry.id == id } ?: return
        val failure = row.commit.value as? CommitPhase.Failed ?: return
        if (!MigrationRowRules.canRetry(failure, state.value.isCommitting)) return
        screenModelScope.launchIO { commitRow(row, failure.replace, failure.flags, failure.fromBatch) }
    }

    /**
     * The one commit path: resolve, migrate, record the outcome on the row. Resolving here rather
     * than at accept keeps a bulk accept free and is a no-op for an already-resolved candidate; a
     * cancelled commit is marked failed because the engines are not transactional and the row may
     * be half-applied.
     */
    private suspend fun commitRow(
        row: MigratingEntryRow,
        replace: Boolean,
        flags: Set<MigrationDataFlag>,
        fromBatch: Boolean,
    ) {
        val target = row.chosen.value ?: return
        row.commit.value = CommitPhase.Committing(replace)
        try {
            val resolved = adapter.resolve(target) ?: error("target failed to resolve")
            row.chosen.value = resolved
            adapter.migrate(row.entry, resolved, replace, flags)
            row.commit.value = CommitPhase.Migrated(resolved, replace)
            mutableState.update { it.copy(migratedCount = it.migratedCount + 1) }
        } catch (e: CancellationException) {
            row.commit.value = CommitPhase.Failed(replace, flags, fromBatch)
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Migration failed for ${row.entry.id}" }
            row.commit.value = CommitPhase.Failed(replace, flags, fromBatch)
        }
    }

    fun cancelCommit() {
        commitJob?.cancel()
        commitJob = null
    }

    fun showConfirm(replace: Boolean) = mutableState.update {
        it.copy(dialog = Dialog.Confirm(replace, it.committableCount, it.untouchedCount))
    }

    fun showExitConfirm() = mutableState.update { it.copy(dialog = Dialog.Exit) }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    override fun onDispose() {
        super.onDispose()
        // Row scopes are detached, so they outlive the model unless cancelled here.
        rows.forEach { it.scope.cancel() }
    }

    sealed interface Dialog {
        data class Confirm(val replace: Boolean, val count: Int, val untouched: Int) : Dialog
        data class Progress(val done: Int, val total: Int) : Dialog
        data object Exit : Dialog
    }

    data class State(
        val isLoading: Boolean = true,
        val tuning: MigrationTuning = MigrationTuning(),
        val rows: List<MigratingEntryRow> = emptyList(),
        val searchedCount: Int = 0,
        val migratedCount: Int = 0,
        val isCommitting: Boolean = false,
        val finished: Boolean = false,
        val savedFlags: Set<MigrationDataFlag> = emptySet(),
        val dialog: Dialog? = null,
    ) {
        val rowIds: Set<EntryId> = rows.mapTo(HashSet()) { it.entry.id }

        /** Every row has settled, so the totals the commit bar shows are final. */
        val allSearched: Boolean get() = rows.isNotEmpty() && rows.all { it.isSettled }

        val committableCount: Int
            get() = rows.count { MigrationRowRules.isCommittable(it.chosen.value, it.commit.value, it.skipped.value) }

        /** Rows a commit would leave alone: skipped, or with nothing to migrate onto. */
        val untouchedCount: Int
            get() = rows.count { !it.commit.value.isDone && (it.skipped.value || it.chosen.value == null) }
    }
}

/** Settled for progress purposes: searched, or skipped out of the batch. */
private val MigratingEntryRow.isSettled: Boolean
    get() = skipped.value || search.value.isSettled
