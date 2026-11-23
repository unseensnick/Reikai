package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.migrate.flow.MigratingEntryRow.Acceptance
import reikai.presentation.migrate.flow.MigratingEntryRow.CommitPhase
import reikai.presentation.migrate.flow.MigratingEntryRow.SearchPhase
import reikai.presentation.migrate.flow.MigrationRowRules.RowActions
import reikai.presentation.migrate.flow.MigrationRowRules.RowStatus
import tachiyomi.domain.manga.model.Manga

/**
 * Covers each transition the rules forbid, so a relaxed guard fails here rather than surfacing as a
 * contradictory row (a skip landing on a committing row, a pick re-arming a migrated one).
 */
class MigrationRowRulesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("hides")
    fun `the hide toggles drop only an untouched settled row`(
        name: String,
        search: SearchPhase,
        acceptance: Acceptance,
        entryLatest: Double?,
        expanded: Boolean,
        tuning: MigrationTuning,
        expected: Boolean,
    ) {
        MigrationRowRules.shouldHide(search, acceptance, entryLatest, expanded, tuning) shouldBe expected
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("permissions")
    fun `a row allows each transition only in the phases the rules name`(
        name: String,
        allows: () -> Boolean,
        expected: Boolean,
    ) {
        allows() shouldBe expected
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("statuses")
    fun `the status line reports the commit outcome, then the target, then the search`(
        name: String,
        search: SearchPhase,
        acceptance: Acceptance,
        commit: CommitPhase,
        expected: RowStatus,
    ) {
        MigrationRowRules.status(search, acceptance, commit) shouldBe expected
    }

    // The screen renders a control only where its flag is true, so each row pins every flag at once.
    @ParameterizedTest(name = "{0}")
    @MethodSource("rowActions")
    fun `a row offers exactly the controls its handlers accept`(
        name: String,
        search: SearchPhase,
        acceptance: Acceptance,
        commit: CommitPhase,
        busy: Boolean,
        expected: RowActions,
    ) {
        MigrationRowRules.actions(search, acceptance, commit, busy) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    fun `a row's target is the accepted one, else the suggestion`(
        name: String,
        search: SearchPhase,
        acceptance: Acceptance,
        expected: MigrationCandidate?,
    ) {
        MigrationRowRules.target(search, acceptance) shouldBe expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shortfalls")
    fun `a shortfall is reported only for a target known to be behind`(
        name: String,
        entryLatest: Double?,
        targetLatest: Double?,
        expected: Double?,
    ) {
        MigrationRowRules.shortfall(entryLatest, targetLatest) shouldBe expected
    }

    companion object {
        private val candidate = MigrationCandidate(
            sourceKey = "s",
            title = "T",
            chapterCount = 3,
            key = "s:/t",
            handle = MangaCandidateHandle(Manga.create()),
        )
        private val picked = candidate.copy(sourceKey = "picked")
        private val found = SearchPhase.Found(candidate, "Source")
        private val failedCommit = CommitPhase.Failed(
            replace = false,
            flags = setOf(MigrationDataFlag.CHAPTER),
        )
        private val hideBoth = MigrationTuning(hideUnmatched = true, hideWithoutUpdates = true)
        private val accepted = Acceptance.Accepted(candidate)

        private fun foundAt(latestChapter: Double?) =
            SearchPhase.Found(candidate.copy(latestChapter = latestChapter), "Source")

        private fun hide(
            name: String,
            search: SearchPhase,
            expected: Boolean,
            acceptance: Acceptance = Acceptance.Untouched,
            entryLatest: Double? = 10.0,
            expanded: Boolean = false,
            tuning: MigrationTuning = hideBoth,
        ) = Arguments.of(name, search, acceptance, entryLatest, expanded, tuning, expected)

        @JvmStatic
        fun hides() = listOf(
            hide("hide-unmatched drops a row that found nothing", SearchPhase.NoMatch, true),
            hide("an open row survives the hide toggles", SearchPhase.NoMatch, false, expanded = true),
            // The whole reason Declined exists: dropping a row the user just acted on reads as it
            // vanishing under their hands, and Untouched cannot tell that case from "never looked at".
            hide(
                "a target the user gave back keeps the row on screen",
                SearchPhase.NoMatch,
                false,
                Acceptance.Declined,
            ),
            // Late-arriving inputs are what re-hid the row three times: a declined row stays put
            // whichever way the search lands.
            hide(
                "a declined row stays on screen whichever way its search landed",
                foundAt(9.0),
                false,
                Acceptance.Declined,
            ),
            hide(
                "an accepted row survives the hide toggles, or it would commit invisibly",
                SearchPhase.NoMatch,
                false,
                accepted,
            ),
            // A guard, not a clause pin: no branch of shouldHide can drop an unsettled row today. It
            // is here so that adding such a branch, which reads as reasonable, fails instead of
            // dropping rows mid-search.
            hide("a queued row is never dropped", SearchPhase.Queued, false),
            hide("a row being searched is never dropped", SearchPhase.Searching, false),
            hide("an unreachable source is not no match, so a failed search stays", SearchPhase.Failed, false),
            hide("hide-without-updates drops a target that is no further ahead", foundAt(9.0), true),
            hide("hide-without-updates keeps a target that is ahead", foundAt(11.0), false),
            hide("an unknown target count is not evidence, so the row stays", foundAt(null), false),
            hide("an unknown entry count is not evidence, so the row stays", foundAt(9.0), false, entryLatest = null),
            hide("with the toggles off nothing is dropped", SearchPhase.NoMatch, false, tuning = MigrationTuning()),
        )

        private fun allows(name: String, expected: Boolean, allows: () -> Boolean) = Arguments.of(
            name,
            allows,
            expected,
        )

        @JvmStatic
        fun permissions() = listOf(
            allows("a queued row the driver can still reach is not settled", false) {
                MigrationRowRules.isSettled(SearchPhase.Queued, CommitPhase.Idle)
            },
            allows("a row searching right now is not settled", false) {
                MigrationRowRules.isSettled(SearchPhase.Searching, CommitPhase.Idle)
            },
            allows("a queued row whose commit failed is settled, since it can never be searched again", true) {
                MigrationRowRules.isSettled(SearchPhase.Queued, failedCommit)
            },
            allows("a queued row mid-commit is settled, since the driver cannot touch it", true) {
                MigrationRowRules.isSettled(SearchPhase.Queued, CommitPhase.Committing(replace = true))
            },
            allows("a queued row with an idle commit is searchable", true) {
                MigrationRowRules.canSearch(SearchPhase.Queued, CommitPhase.Idle)
            },
            allows("a settled row is not searched again", false) {
                MigrationRowRules.canSearch(found, CommitPhase.Idle)
            },
            allows("a committing row takes no new target", false) {
                MigrationRowRules.canChoose(CommitPhase.Committing(replace = true))
            },
            allows("a failed row can be re-targeted before its retry", true) {
                MigrationRowRules.canChoose(failedCommit)
            },
            allows("un-accept is offered while the commit is idle, whatever the search found", true) {
                MigrationRowRules.canUnchoose(CommitPhase.Idle)
            },
            allows("un-accept is blocked on a failed row so its retry keeps a target", false) {
                MigrationRowRules.canUnchoose(failedCommit)
            },
            allows("un-accept is blocked mid-commit", false) {
                MigrationRowRules.canUnchoose(CommitPhase.Committing(replace = false))
            },
            allows("skip is blocked while the row is committing", false) {
                MigrationRowRules.canSkip(CommitPhase.Committing(replace = false))
            },
            allows("skip is allowed on a failed row", true) { MigrationRowRules.canSkip(failedCommit) },
            allows("a targetless row is excluded from a commit", false) {
                MigrationRowRules.isCommittable(Acceptance.Untouched, CommitPhase.Idle)
            },
            allows("an accepted row with an idle commit is committable", true) {
                MigrationRowRules.isCommittable(accepted, CommitPhase.Idle)
            },
            allows("retry is offered only for a failed commit", false) {
                MigrationRowRules.canRetry(CommitPhase.Idle, anyCommitInFlight = false)
            },
            allows("retry waits while another commit runs", false) {
                MigrationRowRules.canRetry(failedCommit, anyCommitInFlight = true)
            },
            allows("retry is offered on a failed row when nothing else commits", true) {
                MigrationRowRules.canRetry(failedCommit, anyCommitInFlight = false)
            },
        )

        @JvmStatic
        fun statuses() = listOf(
            // The regression this exists for: every source throwing rendered exactly like "no source
            // has this title", and since the driver never re-searches a settled row, a passing outage
            // looked like a permanent verdict.
            Arguments.of(
                "a search that failed does not read as no match",
                SearchPhase.Failed,
                Acceptance.Untouched,
                CommitPhase.Idle,
                RowStatus.SearchFailed,
            ),
            Arguments.of(
                "a search that found nothing reads as no match",
                SearchPhase.NoMatch,
                Acceptance.Untouched,
                CommitPhase.Idle,
                RowStatus.NoMatch,
            ),
            Arguments.of(
                "a failed commit outranks the search outcome",
                found,
                Acceptance.Untouched,
                failedCommit,
                RowStatus.CommitFailed,
            ),
            Arguments.of(
                "a running commit outranks the search outcome",
                found,
                Acceptance.Untouched,
                CommitPhase.Committing(replace = true),
                RowStatus.Committing,
            ),
            Arguments.of(
                "an accepted target is reported over the suggestion it replaced",
                found,
                Acceptance.Accepted(picked),
                CommitPhase.Idle,
                RowStatus.Target("picked"),
            ),
            Arguments.of(
                "a row not searched yet reports nothing",
                SearchPhase.Queued,
                Acceptance.Untouched,
                CommitPhase.Idle,
                RowStatus.Idle,
            ),
            Arguments.of(
                "a row being searched reports searching",
                SearchPhase.Searching,
                Acceptance.Untouched,
                CommitPhase.Idle,
                RowStatus.Searching,
            ),
        )

        private fun offers(
            accept: Boolean = false,
            unaccept: Boolean = false,
            skip: Boolean = true,
            retry: Boolean = false,
            commitNow: Boolean = false,
            pick: Boolean = true,
        ) = RowActions(accept, unaccept, skip, retry, commitNow, pick)

        @JvmStatic
        fun rowActions() = listOf(
            Arguments.of(
                "a suggested row offers accept and nothing to un-accept",
                found,
                Acceptance.Untouched,
                CommitPhase.Idle,
                false,
                offers(accept = true),
            ),
            // Its search still carries a suggestion, so only the already-accepted clause refuses accept.
            Arguments.of(
                "an accepted row offers un-accept and a single commit, and nothing more to accept",
                found,
                accepted,
                CommitPhase.Idle,
                false,
                offers(unaccept = true, commitNow = true),
            ),
            Arguments.of(
                "a row that found nothing offers nothing to accept and no single commit",
                SearchPhase.NoMatch,
                Acceptance.Untouched,
                CommitPhase.Idle,
                false,
                offers(),
            ),
            Arguments.of(
                "a row whose search failed offers nothing to accept",
                SearchPhase.Failed,
                Acceptance.Untouched,
                CommitPhase.Idle,
                false,
                offers(),
            ),
            // The dead-control class: these all rendered while their handlers refused.
            Arguments.of(
                "nothing that commits is offered while another commit runs",
                found,
                accepted,
                failedCommit,
                true,
                offers(),
            ),
            Arguments.of(
                "a failed row offers retry and a single commit when nothing else commits",
                found,
                accepted,
                failedCommit,
                false,
                offers(retry = true, commitNow = true),
            ),
            // The dead-control class again: the picker rendered off the row own expanded flag, so a row
            // mid-commit kept one whose every candidate was silently refused and could not be closed.
            Arguments.of(
                "a committing row offers no skip and no picker",
                found,
                Acceptance.Untouched,
                CommitPhase.Committing(replace = true),
                false,
                offers(skip = false, pick = false),
            ),
            Arguments.of(
                "a failed row may still be re-targeted from the picker before its retry",
                found,
                Acceptance.Untouched,
                failedCommit,
                false,
                offers(accept = true, retry = true),
            ),
        )

        @JvmStatic
        fun targets() = listOf(
            Arguments.of(
                "an accepted target is the row's target over the suggestion",
                found,
                Acceptance.Accepted(picked),
                picked,
            ),
            Arguments.of("a row with no accepted target shows its suggestion", found, Acceptance.Declined, candidate),
            Arguments.of(
                "a row with neither an accepted target nor a suggestion has no target",
                SearchPhase.NoMatch,
                Acceptance.Untouched,
                null,
            ),
        )

        @JvmStatic
        fun shortfalls() = listOf(
            Arguments.of("a target behind the entry reports the signed shortfall", 584.0, 120.0, -464.0),
            Arguments.of("a target level with the entry has no shortfall", 10.0, 10.0, null),
            Arguments.of("a target ahead of the entry has no shortfall", 10.0, 12.0, null),
            Arguments.of("an unknown entry latest chapter is no shortfall", null, 3.0, null),
            Arguments.of("an unknown target latest chapter is no shortfall", 3.0, null, null),
        )
    }
}
