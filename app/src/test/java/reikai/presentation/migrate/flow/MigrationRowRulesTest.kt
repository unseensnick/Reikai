package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.migrate.flow.MigratingEntryRow.Acceptance
import reikai.presentation.migrate.flow.MigratingEntryRow.CommitPhase
import reikai.presentation.migrate.flow.MigratingEntryRow.SearchPhase

/**
 * Covers each transition the rules forbid, so a relaxed guard fails here rather than surfacing as a
 * contradictory row (a skip landing on a committing row, a pick re-arming a migrated one).
 */
class MigrationRowRulesTest {

    private val candidate = MigrationCandidate(
        sourceKey = "s",
        title = "T",
        chapterCount = 3,
        key = "s:/t",
        handle = Any(),
    )
    private val found = SearchPhase.Found(candidate, "Source")
    private val failedCommit = CommitPhase.Failed(
        replace = false,
        flags = setOf(MigrationDataFlag.CHAPTER),
    )

    private val hideBoth = MigrationTuning(hideUnmatched = true, hideWithoutUpdates = true)

    private val accepted = Acceptance.Accepted(candidate)

    private fun visible(
        search: SearchPhase,
        acceptance: Acceptance = Acceptance.Untouched,
        entryLatest: Double? = 10.0,
        expanded: Boolean = false,
        tuning: MigrationTuning = hideBoth,
    ) = MigrationRowRules.isVisible(search, acceptance, entryLatest, expanded, tuning)

    @Test
    fun `hide-unmatched hides a row that found nothing`() {
        visible(SearchPhase.NoMatch) shouldBe false
    }

    @Test
    fun `an open row survives the hide toggles`() {
        visible(SearchPhase.NoMatch, expanded = true) shouldBe true
    }

    @Test
    fun `a target the user gave back keeps the row on screen`() {
        // The whole reason Declined exists: hiding a row the user just acted on reads as it
        // vanishing under their hands, and Untouched cannot tell that case from "never looked at".
        visible(SearchPhase.NoMatch, Acceptance.Declined) shouldBe true
    }

    @Test
    fun `a declined row stays on screen whichever way its search landed`() {
        val behind = SearchPhase.Found(candidate.copy(latestChapter = 9.0), "Source")

        // Late-arriving filter inputs are what re-hid the row three times: a declined row stays put
        // whichever way the search lands.
        visible(behind, Acceptance.Declined, entryLatest = 10.0) shouldBe true
    }

    @Test
    fun `an accepted row survives the hide toggles, or it would commit invisibly`() {
        visible(SearchPhase.NoMatch, accepted) shouldBe true
    }

    @Test
    fun `a row still being searched is never hidden`() {
        // A guard, not a clause pin: no branch of isVisible can hide an unsettled row today, and
        // deleting any one of them leaves this green. It is here so that adding such a branch, which
        // reads as a reasonable thing to do, fails instead of blanking rows mid-search.
        visible(SearchPhase.Queued) shouldBe true
        visible(SearchPhase.Searching) shouldBe true
    }

    @Test
    fun `only an untouched row counts as untouched`() {
        val disposition = MigrationRowRules::disposition

        disposition(Acceptance.Untouched) shouldBe MigrationRowRules.Disposition.Untouched
        disposition(accepted) shouldBe MigrationRowRules.Disposition.Armed
        // A decided row leaves the list, so a handed-back target is the only thing left to settle
        // one in place. The gate, accept-all and the hide toggles all read this.
        disposition(Acceptance.Declined) shouldBe MigrationRowRules.Disposition.Settled
    }

    @Test
    fun `an unreachable source is not the same as no match, so a failed search stays`() {
        visible(SearchPhase.Failed) shouldBe true
    }

    @Test
    fun `hide-without-updates hides a target that is no further ahead`() {
        val behind = SearchPhase.Found(candidate.copy(latestChapter = 9.0), "Source")

        visible(behind, entryLatest = 10.0) shouldBe false
    }

    @Test
    fun `hide-without-updates keeps a target that is ahead`() {
        val ahead = SearchPhase.Found(candidate.copy(latestChapter = 11.0), "Source")

        visible(ahead, entryLatest = 10.0) shouldBe true
    }

    @Test
    fun `an unknown count on either side is not evidence, so the row stays`() {
        val unknownTarget = SearchPhase.Found(candidate.copy(latestChapter = null), "Source")
        val knownTarget = SearchPhase.Found(candidate.copy(latestChapter = 9.0), "Source")

        visible(unknownTarget, entryLatest = 10.0) shouldBe true
        visible(knownTarget, entryLatest = null) shouldBe true
    }

    @Test
    fun `with the toggles off nothing is hidden`() {
        visible(SearchPhase.NoMatch, tuning = MigrationTuning()) shouldBe true
    }

    @Test
    fun `a queued row the driver can still reach is not settled`() {
        MigrationRowRules.isSettled(SearchPhase.Queued, CommitPhase.Idle) shouldBe false
    }

    @Test
    fun `a row searching right now is not settled`() {
        MigrationRowRules.isSettled(SearchPhase.Searching, CommitPhase.Idle) shouldBe false
    }

    @Test
    fun `a queued row whose commit failed is settled, since it can never be searched again`() {
        MigrationRowRules.isSettled(SearchPhase.Queued, failedCommit) shouldBe true
    }

    @Test
    fun `a queued row mid-commit is settled, since the driver cannot touch it`() {
        MigrationRowRules.isSettled(SearchPhase.Queued, CommitPhase.Committing(replace = true))
            .shouldBe(true)
    }

    @Test
    fun `a queued row with an idle commit is searchable`() {
        MigrationRowRules.canSearch(SearchPhase.Queued, CommitPhase.Idle) shouldBe true
    }

    @Test
    fun `a settled row is not searched again`() {
        MigrationRowRules.canSearch(found, CommitPhase.Idle) shouldBe false
    }

    @Test
    fun `a committing row takes no new target`() {
        MigrationRowRules.canChoose(CommitPhase.Committing(replace = true)) shouldBe false
    }

    @Test
    fun `a failed row can be re-targeted before its retry`() {
        MigrationRowRules.canChoose(failedCommit) shouldBe true
    }

    @Test
    fun `un-accept is offered while the commit is idle, whatever the search found`() {
        MigrationRowRules.canUnchoose(CommitPhase.Idle) shouldBe true
    }

    @Test
    fun `un-accept is blocked on a failed row so its retry keeps a target`() {
        MigrationRowRules.canUnchoose(failedCommit) shouldBe false
    }

    @Test
    fun `un-accept is blocked mid-commit`() {
        MigrationRowRules.canUnchoose(CommitPhase.Committing(replace = false)) shouldBe false
    }

    @Test
    fun `skip is blocked while the row is committing`() {
        MigrationRowRules.canSkip(CommitPhase.Committing(replace = false)) shouldBe false
    }

    @Test
    fun `skip is allowed on a failed row`() {
        MigrationRowRules.canSkip(failedCommit) shouldBe true
    }

    @Test
    fun `a targetless row is excluded from a commit`() {
        MigrationRowRules.isCommittable(Acceptance.Untouched, CommitPhase.Idle) shouldBe false
    }

    @Test
    fun `an accepted row with an idle commit is committable`() {
        MigrationRowRules.isCommittable(accepted, CommitPhase.Idle) shouldBe true
    }

    @Test
    fun `retry is offered only for a failed commit`() {
        MigrationRowRules.canRetry(CommitPhase.Idle, anyCommitInFlight = false) shouldBe false
    }

    @Test
    fun `retry waits while another commit runs`() {
        MigrationRowRules.canRetry(failedCommit, anyCommitInFlight = true) shouldBe false
    }

    @Test
    fun `retry is offered on a failed row when nothing else commits`() {
        MigrationRowRules.canRetry(failedCommit, anyCommitInFlight = false) shouldBe true
    }

    @Test
    fun `a search restart re-queues an idle row`() {
        MigrationRowRules.onSearchRestart(CommitPhase.Idle) shouldBe MigrationRowRules.RestartOutcome.Requeue
    }

    @Test
    fun `a search restart spares a committing row`() {
        MigrationRowRules.onSearchRestart(CommitPhase.Committing(replace = true)) shouldBe
            MigrationRowRules.RestartOutcome.Keep
    }

    @Test
    fun `a type without smart matching drops the options it cannot run`() {
        val edited = MigrationTuning(deepSearch = true, prioritizeByChapters = true, extraQuery = "vol 2")

        val normalized = edited.normalizedFor(MatchStrategy.BestTitleMatch)

        // Accepted, persisted nowhere and read back as false, having already bought a full row
        // rebuild on the way through: the sheet hiding the checkboxes was the only thing stopping it.
        normalized.deepSearch shouldBe false
        normalized.prioritizeByChapters shouldBe false
        normalized.extraQuery shouldBe "vol 2"
        normalized.affectsSearch(MigrationTuning(extraQuery = "vol 2")) shouldBe false
    }

    @Test
    fun `smart matching keeps them`() {
        val edited = MigrationTuning(deepSearch = true, prioritizeByChapters = true)

        edited.normalizedFor(MatchStrategy.Smart) shouldBe edited
    }

    @Test
    fun `hide toggles alone never require a re-search`() {
        val base = MigrationTuning(hideUnmatched = false)
        base.affectsSearch(base.copy(hideUnmatched = true, hideWithoutUpdates = true)) shouldBe false
    }

    @Test
    fun `an edited extra query requires a re-search`() {
        MigrationTuning().affectsSearch(MigrationTuning(extraQuery = "vol 2")) shouldBe true
    }

    @Test
    fun `toggling deep search requires a re-search`() {
        MigrationTuning().affectsSearch(MigrationTuning(deepSearch = true)) shouldBe true
    }

    @Test
    fun `a settled search reports settled and a running one does not`() {
        found.isSettled shouldBe true
        SearchPhase.Searching.isSettled shouldBe false
    }

    @Test
    fun `the suggestion is readable only from a found search`() {
        found.suggestion shouldBe candidate
        SearchPhase.NoMatch.suggestion shouldBe null
    }

    @Test
    fun `the accepted target is readable only from an accepted row`() {
        accepted.candidate shouldBe candidate
        Acceptance.Untouched.candidate shouldBe null
        Acceptance.Declined.candidate shouldBe null
    }

    private fun status(
        search: SearchPhase = found,
        acceptance: Acceptance = Acceptance.Untouched,
        commit: CommitPhase = CommitPhase.Idle,
    ) = MigrationRowRules.status(search, acceptance, commit)

    @Test
    fun `a search that failed does not read as no match`() {
        // The regression this case exists for: every source throwing rendered exactly like "no source
        // has this title", and since the driver never re-searches a settled row, a passing outage
        // looked like a permanent verdict.
        status(search = SearchPhase.Failed) shouldBe MigrationRowRules.RowStatus.SearchFailed
        status(search = SearchPhase.NoMatch) shouldBe MigrationRowRules.RowStatus.NoMatch
    }

    @Test
    fun `a commit outcome outranks the search outcome`() {
        status(commit = failedCommit, search = found) shouldBe MigrationRowRules.RowStatus.CommitFailed
        status(commit = CommitPhase.Committing(replace = true), search = found) shouldBe
            MigrationRowRules.RowStatus.Committing
    }

    @Test
    fun `an accepted target is reported over the suggestion it replaced`() {
        val other = candidate.copy(sourceKey = "picked")

        status(acceptance = Acceptance.Accepted(other), search = found) shouldBe
            MigrationRowRules.RowStatus.Target("picked")
    }

    @Test
    fun `a row that has not been searched yet reports nothing`() {
        status(search = SearchPhase.Queued) shouldBe MigrationRowRules.RowStatus.Idle
        status(search = SearchPhase.Searching) shouldBe MigrationRowRules.RowStatus.Searching
    }

    private fun actions(
        search: SearchPhase = found,
        acceptance: Acceptance = Acceptance.Untouched,
        commit: CommitPhase = CommitPhase.Idle,
        busy: Boolean = false,
    ) = MigrationRowRules.actions(search, acceptance, commit, busy)

    @Test
    fun `a suggested row offers accept and nothing to un-accept`() {
        val offered = actions()

        offered.canAccept shouldBe true
        offered.canUnaccept shouldBe false
    }

    @Test
    fun `an accepted row offers un-accept and a single commit`() {
        val offered = actions(acceptance = accepted)

        offered.canUnaccept shouldBe true
        offered.canCommitNow shouldBe true
    }

    @Test
    fun `nothing that commits is offered while another commit runs`() {
        // The dead-control class: these all rendered while their handlers refused.
        val offered = actions(acceptance = accepted, commit = failedCommit, busy = true)

        offered.canCommitNow shouldBe false
        offered.canRetry shouldBe false
    }

    @Test
    fun `a row with no target never offers a single commit`() {
        actions(search = SearchPhase.NoMatch).canCommitNow shouldBe false
    }

    @Test
    fun `skip is not offered mid-commit`() {
        actions(commit = CommitPhase.Committing(replace = true)).canSkip shouldBe false
    }

    @Test
    fun `a failed row offers retry when nothing else commits`() {
        actions(acceptance = accepted, commit = failedCommit).canRetry shouldBe true
    }

    @Test
    fun `a committing row offers no picker`() {
        // The dead-control class again: the picker rendered off the row own expanded flag, so a row
        // mid-commit kept one whose every candidate was silently refused and could not be closed.
        actions(commit = CommitPhase.Committing(replace = true)).canPick shouldBe false
    }

    @Test
    fun `a failed row may still be re-targeted from the picker before its retry`() {
        actions(commit = failedCommit).canPick shouldBe true
    }
}
