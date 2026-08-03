package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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
        resolved = true,
    )
    private val found = SearchPhase.Found(candidate, "Source")
    private val failedCommit = CommitPhase.Failed(
        replace = false,
        flags = setOf(MigrationDataFlag.CHAPTER),
        fromBatch = true,
    )
    private val migrated = CommitPhase.Migrated(candidate, replace = true)

    @Test
    fun `a queued unskipped row is searchable`() {
        MigrationRowRules.canSearch(SearchPhase.Queued, CommitPhase.Idle, skipped = false) shouldBe true
    }

    @Test
    fun `a skipped row is not searched`() {
        MigrationRowRules.canSearch(SearchPhase.Queued, CommitPhase.Idle, skipped = true) shouldBe false
    }

    @Test
    fun `a settled row is not searched again`() {
        MigrationRowRules.canSearch(found, CommitPhase.Idle, skipped = false) shouldBe false
    }

    @Test
    fun `a migrated row is never re-searched`() {
        MigrationRowRules.canSearch(SearchPhase.Queued, migrated, skipped = false) shouldBe false
    }

    @Test
    fun `a migrated row takes no new target`() {
        MigrationRowRules.canChoose(migrated) shouldBe false
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
    fun `un-accept needs a suggestion to fall back to`() {
        MigrationRowRules.canUnchoose(SearchPhase.NoMatch, CommitPhase.Idle) shouldBe false
    }

    @Test
    fun `un-accept is offered on a found row`() {
        MigrationRowRules.canUnchoose(found, CommitPhase.Idle) shouldBe true
    }

    @Test
    fun `un-accept is blocked on a failed row so its retry keeps a target`() {
        MigrationRowRules.canUnchoose(found, failedCommit) shouldBe false
    }

    @Test
    fun `skip is blocked while the row is committing`() {
        MigrationRowRules.canToggleSkip(CommitPhase.Committing(replace = false)) shouldBe false
    }

    @Test
    fun `skip is blocked once the row has migrated`() {
        MigrationRowRules.canToggleSkip(migrated) shouldBe false
    }

    @Test
    fun `skip is allowed on a failed row`() {
        MigrationRowRules.canToggleSkip(failedCommit) shouldBe true
    }

    @Test
    fun `a skipped row is excluded from a commit`() {
        MigrationRowRules.isCommittable(candidate, CommitPhase.Idle, skipped = true) shouldBe false
    }

    @Test
    fun `a targetless row is excluded from a commit`() {
        MigrationRowRules.isCommittable(null, CommitPhase.Idle, skipped = false) shouldBe false
    }

    @Test
    fun `an already migrated row is excluded from a re-commit`() {
        MigrationRowRules.isCommittable(candidate, migrated, skipped = false) shouldBe false
    }

    @Test
    fun `an accepted unskipped row is committable`() {
        MigrationRowRules.isCommittable(candidate, CommitPhase.Idle, skipped = false) shouldBe true
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
    fun `a search restart spares a migrated row`() {
        MigrationRowRules.onSearchRestart(migrated) shouldBe MigrationRowRules.RestartOutcome.Keep
    }

    @Test
    fun `a search restart spares a committing row`() {
        MigrationRowRules.onSearchRestart(CommitPhase.Committing(replace = true)) shouldBe
            MigrationRowRules.RestartOutcome.Keep
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
}
