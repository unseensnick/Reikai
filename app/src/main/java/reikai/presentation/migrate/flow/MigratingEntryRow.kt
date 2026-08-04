package reikai.presentation.migrate.flow

import kotlinx.coroutines.CoroutineDispatcher
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
    // The row's work is a network search and its parsing, so it belongs on IO rather than the CPU
    // pool the first cut used; taking it as a parameter also lets a test drive rows on its own
    // scheduler.
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val scope = CoroutineScope(parentContext + SupervisorJob() + dispatcher)

    val search = MutableStateFlow<SearchPhase>(SearchPhase.Queued)

    val commit = MutableStateFlow<CommitPhase>(CommitPhase.Idle)

    /** Where the user stands on this row's target: see [Acceptance]. */
    val acceptance = MutableStateFlow<Acceptance>(Acceptance.Untouched)

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

    /**
     * Where the user stands on this row's target, as a third axis beside the search and commit cells.
     *
     * A nullable target could not tell [Untouched] from [Declined], and the hide toggles need to:
     * a row the user has never looked at is exactly what those toggles thin out, while a row whose
     * target they just handed back must stay on screen. Carrying that as a null plus a sticky
     * "keep visible" boolean is what the nullable-soup rule forbids, and it produced the same
     * disappearing-row bug three times.
     *
     * [Accepted] carries the target rather than pointing at one, so "has a target" and "which
     * target" cannot disagree (upstream's `SearchResult.Success` does the same). A tuning re-search
     * builds fresh rows, so [Declined] does not survive it: that is a new list.
     */
    sealed interface Acceptance {
        /** No target chosen yet. A suggestion may exist on the search cell; the user has not acted. */
        data object Untouched : Acceptance

        /** The commit target: what a commit would migrate onto. */
        data class Accepted(val candidate: MigrationCandidate) : Acceptance

        /** The user gave a target back. Not the same as never having had one. */
        data object Declined : Acceptance
    }

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

/** The accepted target, or null when the user has not accepted one. */
val MigratingEntryRow.Acceptance.candidate: MigrationCandidate?
    get() = (this as? MigratingEntryRow.Acceptance.Accepted)?.candidate

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
     * [isSettled] reads this as "the driver will not pick this row up", so a clause that can flip
     * back must have someone who restarts the driver when it does. Both current ones do: un-skipping
     * calls startDriver, and releasing a commit claim does too. A clause with no such waker (a pause
     * flag, a source-availability probe) would strand queued rows as settled with the commit bar
     * open over them; put that in the driver's own loop head, next to its scope-liveness check.
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

    /**
     * Whether the hide toggles leave this row on screen.
     *
     * Always shown: an accepted row (hiding one the user has chosen would commit it invisibly), a
     * [pinned] row (open, or acted on), and a row still searching (its outcome is not known yet, and
     * hiding it the instant one lands is the vanishing-row bug this rule keeps producing). A failed
     * search is shown too: hide-unmatched is about entries with no match, not about entries whose
     * sources were unreachable.
     */
    fun isVisible(
        search: MigratingEntryRow.SearchPhase,
        acceptance: MigratingEntryRow.Acceptance,
        entryLatestChapter: Double?,
        expanded: Boolean,
        tuning: MigrationTuning,
    ): Boolean {
        // Untouched is the only acceptance the toggles may hide: Accepted would commit invisibly and
        // Declined is a row the user just acted on. Reading the state rather than a sticky flag is
        // what stops a late search outcome or a late count from re-hiding it a moment later.
        if (acceptance !is MigratingEntryRow.Acceptance.Untouched || expanded || !search.isSettled) return true
        if (tuning.hideUnmatched && search is MigratingEntryRow.SearchPhase.NoMatch) return false
        if (tuning.hideWithoutUpdates && search is MigratingEntryRow.SearchPhase.Found) {
            val targetLatest = search.suggestion.latestChapter
            // Only hide on a real comparison: an unknown count on either side is not evidence that
            // the target is no further ahead.
            if (targetLatest != null && entryLatestChapter != null && targetLatest <= entryLatestChapter) return false
        }
        return true
    }

    /** A row the batch commit should include: accepted, not skipped, not already migrated. */
    fun isCommittable(
        acceptance: MigratingEntryRow.Acceptance,
        commit: MigratingEntryRow.CommitPhase,
        skipped: Boolean,
    ): Boolean = acceptance is MigratingEntryRow.Acceptance.Accepted && !skipped && !commit.isDone && !commit.isBusy

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

    /**
     * What this row currently offers the user.
     *
     * The single source of truth for the row's controls: the screen renders a control only where the
     * matching flag is true, and the ScreenModel refuses anything else. Deriving both from here is
     * what stops the two from disagreeing, which is how Retry, Skip, "Migrate now" and the accept
     * toggle all came to render on rows whose handlers silently refused them.
     */
    data class RowActions(
        val canAccept: Boolean,
        val canUnaccept: Boolean,
        val canToggleSkip: Boolean,
        val canRetry: Boolean,
        val canCommitNow: Boolean,
    )

    /**
     * What the row's status line reports, as one case per meaning.
     *
     * Derived here rather than branched at the Text call, because two states sharing a rendering is
     * invisible in a `when` that returns strings: a search that failed because every source threw
     * read exactly like "no source has this title", and since the driver never re-searches a settled
     * row, a passing outage looked like a permanent verdict.
     */
    sealed interface RowStatus {
        /** Not searched yet; the row has nothing to report. */
        data object Idle : RowStatus
        data object Searching : RowStatus
        data object NoMatch : RowStatus
        data object SearchFailed : RowStatus

        /** A target is in hand (suggested or accepted); [sourceKey] names where it came from. */
        data class Target(val sourceKey: String) : RowStatus
        data object Skipped : RowStatus
        data object Committing : RowStatus
        data object CommitFailed : RowStatus

        /** Done. Carries the target it migrated onto, so the line still says where it went. */
        data class Migrated(val sourceKey: String) : RowStatus
    }

    fun status(
        search: MigratingEntryRow.SearchPhase,
        acceptance: MigratingEntryRow.Acceptance,
        commit: MigratingEntryRow.CommitPhase,
        skipped: Boolean,
    ): RowStatus = when {
        commit is MigratingEntryRow.CommitPhase.Migrated -> RowStatus.Migrated(commit.target.sourceKey)
        commit is MigratingEntryRow.CommitPhase.Failed -> RowStatus.CommitFailed
        commit is MigratingEntryRow.CommitPhase.Committing -> RowStatus.Committing
        // Skip outranks a search outcome: the escape hatch has to confirm itself visibly.
        skipped -> RowStatus.Skipped
        acceptance is MigratingEntryRow.Acceptance.Accepted -> RowStatus.Target(acceptance.candidate.sourceKey)
        search is MigratingEntryRow.SearchPhase.Found -> RowStatus.Target(search.suggestion.sourceKey)
        search is MigratingEntryRow.SearchPhase.Failed -> RowStatus.SearchFailed
        search is MigratingEntryRow.SearchPhase.NoMatch -> RowStatus.NoMatch
        search is MigratingEntryRow.SearchPhase.Searching -> RowStatus.Searching
        else -> RowStatus.Idle
    }

    fun actions(
        search: MigratingEntryRow.SearchPhase,
        acceptance: MigratingEntryRow.Acceptance,
        commit: MigratingEntryRow.CommitPhase,
        skipped: Boolean,
        anyCommitInFlight: Boolean,
    ): RowActions = RowActions(
        canAccept = acceptance !is MigratingEntryRow.Acceptance.Accepted && !skipped &&
            search.suggestion != null && canChoose(commit),
        canUnaccept = acceptance is MigratingEntryRow.Acceptance.Accepted && canUnchoose(commit),
        canToggleSkip = canToggleSkip(commit),
        canRetry = canRetry(commit, anyCommitInFlight, skipped),
        // A single commit is offered only when the batch would take this row too, and only while
        // nothing else is committing: commitSingle refuses both, so offering it would be a dead tap.
        canCommitNow = isCommittable(acceptance, commit, skipped) && !anyCommitInFlight,
    )
}
