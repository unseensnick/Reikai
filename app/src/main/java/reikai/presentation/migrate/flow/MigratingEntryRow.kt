package reikai.presentation.migrate.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext

/**
 * One entry being migrated: a stable object, never a value copied through a list, so a coroutine
 * holding a row reference can always be told apart from the row the UI renders.
 *
 * - [search] and [commit] are orthogonal axes: a row can be re-searching while a previous commit
 *   failed, so folding them into one cell would be lossy.
 * - [chosen] sits beside the search cell rather than inside it: an accepted target is often not the
 *   suggestion (an override strip or deep-browse pick), and un-accepting restores the suggestion.
 * - [skipped] is orthogonal rather than a removal: a skipped row stays in place, dimmed, keeping its
 *   chosen target so restoring is exact.
 * - [scope] is detached (its own [SupervisorJob]), so cancelling one row can never cancel the batch
 *   driver. Abandoning a whole search generation is done by cancelling rows and building new
 *   objects, which is why the driver needs no lock or epoch counter.
 */
class MigratingEntryRow(
    val entry: MigrationEntry,
    parentContext: CoroutineContext,
) {
    val scope = CoroutineScope(parentContext + SupervisorJob() + Dispatchers.Default)

    val search = MutableStateFlow<SearchPhase>(SearchPhase.Queued)

    val commit = MutableStateFlow<CommitPhase>(CommitPhase.Idle)

    /** The accepted target: what a commit would migrate onto. Null until the user accepts. */
    val chosen = MutableStateFlow<MigrationCandidate?>(null)

    val skipped = MutableStateFlow(false)

    /** Whether the row's override picker is open. Pure UI, independent of every phase above. */
    val expanded = MutableStateFlow(false)

    /** The override picker's own little machine, unrelated to the batch search: a user can search by
     *  hand from any search outcome, including one that already found a match. */
    val overrides = MutableStateFlow<OverrideState>(OverrideState.Idle)

    /** The override search in flight, so a re-search supersedes its predecessor rather than racing
     *  it. Held here rather than in a map keyed by row, since the row is the thing that owns it. */
    @Volatile
    var overrideJob: Job? = null

    /** The row's search for a target on the configured sources. */
    sealed interface SearchPhase {
        /** Not started: the driver has not reached this row yet, or a restore re-queued it. */
        data object Queued : SearchPhase

        data object Searching : SearchPhase

        /** Every configured source answered, none had a match. */
        data object NoMatch : SearchPhase

        /** Every configured source threw. Distinct from [NoMatch] so hide-unmatched cannot bury a
         *  row that failed for network reasons and would match on retry. */
        data object Failed : SearchPhase

        /** A best match, from [sourceName]. The suggestion survives accept and un-accept. */
        data class Found(val suggestion: MigrationCandidate, val sourceName: String?) : SearchPhase
    }

    /** The row's commit lifecycle, independent of [SearchPhase]. */
    sealed interface CommitPhase {
        data object Idle : CommitPhase

        data class Committing(val replace: Boolean) : CommitPhase

        /**
         * The commit threw. Carries what a faithful retry needs, so a retry repeats this commit and
         * not whichever ran last: [replace] is the verb that failed and [flags] the exact set it
         * used. Whether the screen may finish is not a property of the row: it is decided once, by
         * the finish gate.
         */
        data class Failed(
            val replace: Boolean,
            val flags: Set<MigrationDataFlag>,
        ) : CommitPhase

        data class Migrated(val target: MigrationCandidate, val replace: Boolean) : CommitPhase
    }

    /** The override picker's state: one strip per searched source once results land. */
    sealed interface OverrideState {
        data object Idle : OverrideState

        data object Loading : OverrideState

        data class Loaded(val strips: List<OverrideStrip>) : OverrideState
    }

    /**
     * One source's override results. A source that threw keeps its strip carrying [error] instead of
     * disappearing, so a failure reads as a failure rather than as "this source has nothing".
     */
    data class OverrideStrip(
        val sourceKey: String,
        val sourceName: String,
        /** Raw language tag, localized at render (shared header shows it like global search). */
        val sourceLang: String = "",
        val candidates: List<MigrationCandidate>,
        val error: String? = null,
    )
}

/** The suggestion when the search found one, else null. */
val MigratingEntryRow.SearchPhase.suggestion: MigrationCandidate?
    get() = (this as? MigratingEntryRow.SearchPhase.Found)?.suggestion

/** True once the search has settled, whatever the outcome. The commit gate waits on this. */
val MigratingEntryRow.SearchPhase.isSettled: Boolean
    get() = this !is MigratingEntryRow.SearchPhase.Queued && this !is MigratingEntryRow.SearchPhase.Searching

/** True while the row holds a live commit; blocks a second commit and any state reset. */
val MigratingEntryRow.CommitPhase.isBusy: Boolean
    get() = this is MigratingEntryRow.CommitPhase.Committing

/** True once the row has migrated: terminal, and the reason most actions stop being offered. */
val MigratingEntryRow.CommitPhase.isDone: Boolean
    get() = this is MigratingEntryRow.CommitPhase.Migrated

/**
 * The pure transition rules. Every state change goes through these, so the legal transition graph
 * lives in one testable place instead of as guards spread across call sites. A rule answering false
 * means the transition is not legal from that state and the caller leaves the cell alone.
 */
object MigrationRowRules {

    /**
     * Whether the driver may search this row now. A settled row is not re-searched; a migrated or
     * committing row must never have its result blanked underneath the commit.
     *
     * [isSettled] reads this as "will never be searchable again", so every clause here must be
     * permanent for the row. A clause that can flip back (a pause flag, a temporary source outage)
     * would mark every queued row settled and open the commit bar over unsearched rows; such a
     * condition belongs in the driver's own loop head, next to its scope-liveness check, not here.
     */
    fun canSearch(
        search: MigratingEntryRow.SearchPhase,
        commit: MigratingEntryRow.CommitPhase,
        skipped: Boolean,
    ): Boolean =
        search is MigratingEntryRow.SearchPhase.Queued && !skipped && commit == MigratingEntryRow.CommitPhase.Idle

    /**
     * Whether the row's search has reached a resting point, for progress counts and the all-searched
     * gate. Derived from [canSearch] rather than listing terminal phases, because a row the driver
     * will never pick up again is settled whatever the reason: a still-queued row that migrated, or
     * failed, or is committing, can never be searched (canSearch demands an idle commit), and listing
     * the phases instead has twice left such a row unsettled, holding the commit bar shut forever.
     */
    fun isSettled(
        search: MigratingEntryRow.SearchPhase,
        commit: MigratingEntryRow.CommitPhase,
        skipped: Boolean,
    ): Boolean = search.isSettled ||
        (search is MigratingEntryRow.SearchPhase.Queued && !canSearch(search, commit, skipped))

    /** Accepting a candidate. Rejected once the row has migrated or while it is committing, so a
     *  late pick cannot re-arm a finished row. */
    fun canChoose(commit: MigratingEntryRow.CommitPhase): Boolean = commit == MigratingEntryRow.CommitPhase.Idle ||
        commit is MigratingEntryRow.CommitPhase.Failed

    /**
     * Un-accepting gives the target back: the row falls back to its suggestion where the search
     * found one, and to no target otherwise. It needs no suggestion to fall back to, because a
     * target picked from an override strip or the deep picker is exactly the case where the search
     * found nothing, and that control must not render as a no-op. Blocked once a commit is in play:
     * un-accepting a failed row would strand its retry, which needs the target it failed on.
     */
    fun canUnchoose(
        commit: MigratingEntryRow.CommitPhase,
    ): Boolean = commit == MigratingEntryRow.CommitPhase.Idle

    /** Skip and restore. Blocked mid-commit and after migrating: the completion write does not
     *  clear [MigratingEntryRow.skipped], so a skip landing there leaves a dimmed migrated row with
     *  no way back. */
    fun canToggleSkip(commit: MigratingEntryRow.CommitPhase): Boolean = !commit.isBusy && !commit.isDone

    /** A row the batch commit should include: accepted, not skipped, not already migrated. */
    fun isCommittable(
        chosen: MigrationCandidate?,
        commit: MigratingEntryRow.CommitPhase,
        skipped: Boolean,
    ): Boolean = chosen != null && !skipped && !commit.isDone && !commit.isBusy

    /** A retry is offered only for a failed commit, only while nothing else is committing, and
     *  never on a skipped row: skip excludes the row from every commit, including this one. */
    fun canRetry(
        commit: MigratingEntryRow.CommitPhase,
        anyCommitInFlight: Boolean,
        skipped: Boolean,
    ): Boolean = commit is MigratingEntryRow.CommitPhase.Failed && !anyCommitInFlight && !skipped

    /**
     * The row state a search restart produces. A migrated row keeps everything (its result is
     * history, not a suggestion); every other row returns to [MigratingEntryRow.SearchPhase.Queued]
     * with its accepted target and failure memory cleared, because both referred to results this
     * restart is discarding.
     */
    fun onSearchRestart(commit: MigratingEntryRow.CommitPhase): RestartOutcome = when {
        commit.isDone || commit.isBusy -> RestartOutcome.Keep
        else -> RestartOutcome.Requeue
    }

    enum class RestartOutcome { Keep, Requeue }
}
