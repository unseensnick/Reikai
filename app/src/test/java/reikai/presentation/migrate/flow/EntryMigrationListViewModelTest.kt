package reikai.presentation.migrate.flow

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId

/**
 * The driver, the row claim and the finish gate, which had no coverage at all until the ScreenModel
 * took its dependencies as constructor parameters: every defect in them was found by reading the
 * code in an audit, never by a test.
 */
class EntryMigrationListViewModelTest {

    // Voyager's screenModelScope is Main-based, and the model launches its work on the dispatcher it
    // is given, so both go through one scheduler the test can advance.
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun entry(id: Long) = migrationEntry(id)

    private fun model(
        entries: List<MigrationEntry>,
        failFor: Set<EntryId> = emptySet(),
        blockOn: EntryId? = null,
        extraQuery: String? = null,
    ) =
        EntryMigrationListViewModel(
            entryIds = entries.map { it.id.rawId },
            adapter = FakeMigrationFlowAdapter(entries, failFor, blockOn),
            pickHandoff = MigrationPickHandoff(),
            extraQuery = extraQuery,
            io = dispatcher,
        )

    @Test
    fun `the driver searches every row and settles the list`() = runTest(dispatcher.scheduler) {
        val model = model(listOf(entry(1), entry(2)))

        advanceUntilIdle()

        model.state.value.allSearched shouldBe true
        model.state.value.searchedCount shouldBe 2
    }

    @Test
    fun `the extra query reaches the search the batch runs`() = runTest(dispatcher.scheduler) {
        // It was typed on the config screen and then dropped: no adapter persists it, so the tuning
        // the model read back always had a null one and the option did nothing on this route.
        val adapter = FakeMigrationFlowAdapter(listOf(entry(1)))
        EntryMigrationListViewModel(
            entryIds = listOf(1L),
            adapter = adapter,
            pickHandoff = MigrationPickHandoff(),
            extraQuery = "vol 2",
            io = dispatcher,
        )

        advanceUntilIdle()

        adapter.suggestedWith.map { it.extraQuery } shouldBe listOf("vol 2")
    }

    @Test
    fun `the picker's strips are on screen before any source is asked`() = runTest(dispatcher.scheduler) {
        // They were published inside the search coroutine, behind a check against a job handle that
        // is only assigned after launch returns, so a search that got going first threw its own
        // results away and left the picker spinning for good.
        val model = model(listOf(entry(1)))
        advanceUntilIdle()

        model.searchOverrides(EntryId.Manga(1), "some title")

        val overrides = model.state.value.rows.single().overrides.value
        overrides.shouldBeInstanceOf<MigratingEntryRow.OverrideState.Strips>()
        overrides.strips.map { it.result } shouldBe listOf(StripResult.Loading)
    }

    @Test
    fun `a manual search on a row sends the extra query with it`() = runTest(dispatcher.scheduler) {
        val adapter = FakeMigrationFlowAdapter(listOf(entry(1)))
        val model = EntryMigrationListViewModel(
            entryIds = listOf(1L),
            adapter = adapter,
            pickHandoff = MigrationPickHandoff(),
            extraQuery = "vol 2",
            io = dispatcher,
        )
        advanceUntilIdle()

        model.searchOverrides(EntryId.Manga(1), "some title")
        advanceUntilIdle()

        adapter.candidateQueries shouldBe listOf("some title vol 2")
    }

    @Test
    fun `a row the hide toggles drop leaves the list, so accept-all cannot arm it`() =
        runTest(dispatcher.scheduler) {
            // It used to stay in rows and only be filtered from the view, and accept-all iterates
            // rows: the hidden row was armed, came back on screen, and would have been migrated.
            val entries = listOf(entry(1), entry(2))
            val model = EntryMigrationListViewModel(
                entryIds = entries.map { it.id.rawId },
                adapter = FakeMigrationFlowAdapter(
                    entries,
                    matchless = setOf(EntryId.Manga(2)),
                    tuning = MigrationTuning(hideUnmatched = true),
                ),
                pickHandoff = MigrationPickHandoff(),
                io = dispatcher,
            )
            advanceUntilIdle()

            model.acceptAll()
            advanceUntilIdle()

            model.state.value.rows.map { it.entry.id } shouldBe listOf(EntryId.Manga(1))
            model.state.value.committableCount shouldBe 1
        }

    @Test
    fun `a count that arrives after the search drops the row it disqualifies`() =
        runTest(dispatcher.scheduler) {
            // The suggestion lands without a chapter number and the peek fills it in, so the toggle's
            // input arrives after the search has settled and the check has to run there too.
            val entries = listOf(entry(1))
            val model = EntryMigrationListViewModel(
                entryIds = entries.map { it.id.rawId },
                adapter = FakeMigrationFlowAdapter(
                    entries,
                    tuning = MigrationTuning(hideWithoutUpdates = true),
                    suggestionLatestChapter = null,
                    // The entry itself is at 1.0, so the target is not ahead of it.
                    peekLatestChapter = 1.0,
                ),
                pickHandoff = MigrationPickHandoff(),
                io = dispatcher,
            )

            advanceUntilIdle()

            model.state.value.rows.shouldBeEmpty()
        }

    @Test
    fun `a clean batch migrates every accepted row and finishes the screen`() = runTest(dispatcher.scheduler) {
        val model = model(listOf(entry(1), entry(2)))
        advanceUntilIdle()

        model.acceptAll()
        model.commit(replace = true, flags = emptySet())
        advanceUntilIdle()

        model.state.value.migratedCount shouldBe 2
        model.state.value.finished shouldBe true
    }

    @Test
    fun `a batch with a failure keeps the screen open, with the failed row still committable`() =
        runTest(dispatcher.scheduler) {
            val model = model(listOf(entry(1), entry(2)), failFor = setOf(EntryId.Manga(2)))
            advanceUntilIdle()

            model.acceptAll()
            model.commit(replace = true, flags = emptySet())
            advanceUntilIdle()

            model.state.value.migratedCount shouldBe 1
            model.state.value.finished shouldBe false
            // Why it stays open, and the reason the gate needs no failure clause of its own: the row
            // kept its target and cannot be un-accepted mid-commit, so it is still committable and a
            // retry has something to run.
            model.state.value.committableCount shouldBe 1
        }

    @Test
    fun `skipping the last failed row finishes what the batch started`() = runTest(dispatcher.scheduler) {
        val model = model(listOf(entry(1), entry(2)), failFor = setOf(EntryId.Manga(2)))
        advanceUntilIdle()
        model.acceptAll()
        model.commit(replace = true, flags = emptySet())
        advanceUntilIdle()

        model.skipRow(EntryId.Manga(2))
        advanceUntilIdle()

        model.state.value.finished shouldBe true
    }

    @Test
    fun `a single commit on its own never finishes the screen`() = runTest(dispatcher.scheduler) {
        // No batch ran, so the user is still working the list: the gate must not pop it.
        val model = model(listOf(entry(1), entry(2)))
        advanceUntilIdle()

        model.toggleAccept(EntryId.Manga(1))
        model.commitSingle(EntryId.Manga(1), replace = true)
        advanceUntilIdle()

        model.state.value.migratedCount shouldBe 1
        model.state.value.finished shouldBe false
    }

    @Test
    fun `a cancelled batch's un-run rows keep the screen open while they still hold a target`() =
        runTest(dispatcher.scheduler) {
            // Rows the batch never reached are still its business: a later single commit must not
            // pop the list from under a row that is still armed and waiting.
            val entries = listOf(entry(1), entry(2), entry(3), entry(4))
            val model = model(entries, blockOn = EntryId.Manga(2))
            advanceUntilIdle()

            listOf(1L, 2L, 3L).forEach { model.toggleAccept(EntryId.Manga(it)) }
            model.commit(replace = true, flags = emptySet())
            advanceUntilIdle()

            // Row 1 migrated, row 2 is stuck mid-commit, row 3 was never reached and stays armed.
            model.cancelCommit()
            advanceUntilIdle()
            model.skipRow(EntryId.Manga(2))
            advanceUntilIdle()

            model.toggleAccept(EntryId.Manga(4))
            model.commitSingle(EntryId.Manga(4), replace = true)
            advanceUntilIdle()

            model.state.value.finished shouldBe false
        }

    @Test
    fun `declining an un-run row settles it, so the screen can finish`() = runTest(dispatcher.scheduler) {
        // Declining says what should happen to the row. Counting it as unfinished business wedged
        // the gate for the rest of the session: nothing that came later could ever resolve it.
        val entries = listOf(entry(1), entry(2), entry(3))
        val model = model(entries, blockOn = EntryId.Manga(2))
        advanceUntilIdle()

        listOf(1L, 2L, 3L).forEach { model.toggleAccept(EntryId.Manga(it)) }
        model.commit(replace = true, flags = emptySet())
        advanceUntilIdle()

        model.cancelCommit()
        advanceUntilIdle()
        model.skipRow(EntryId.Manga(2))
        // Row 3 was never reached; the user hands its target back rather than migrating it.
        model.toggleAccept(EntryId.Manga(3))
        advanceUntilIdle()

        model.state.value.finished shouldBe true
    }

    @Test
    fun `starting a batch replaces the confirm dialog rather than leaving it up`() =
        runTest(dispatcher.scheduler) {
            // Blocked on the FIRST row, so nothing has committed yet: a later row's progress update
            // would otherwise write the cell and hide a dialog that was never cleared.
            val model = model(listOf(entry(1), entry(2)), blockOn = EntryId.Manga(1))
            advanceUntilIdle()
            model.acceptAll()
            model.showConfirm(replace = true)
            advanceUntilIdle()
            model.state.value.dialog.shouldBeInstanceOf<EntryMigrationListViewModel.Dialog.Confirm>()

            model.commit(replace = true, flags = emptySet())
            advanceUntilIdle()

            // The RAW cell, not visibleDialog: the derivation hides a confirm dialog under a running
            // batch anyway, so asserting the derived value passed with the request left in place and
            // pinned nothing. What matters is that starting the batch consumed the request, which is
            // why it cannot come back when the batch ends on a partial failure.
            model.state.value.dialog.shouldBeNull()
            model.state.value.visibleProgress.shouldNotBeNull()
        }

    @Test
    fun `stopping from the exit dialog cancels a per-row commit, not just a batch`() =
        runTest(dispatcher.scheduler) {
            val model = model(listOf(entry(1), entry(2)), blockOn = EntryId.Manga(1))
            advanceUntilIdle()
            model.acceptAll()
            model.commitSingle(EntryId.Manga(1), replace = true)
            advanceUntilIdle()
            model.state.value.isBusy shouldBe true

            // The handle was only ever recorded for the batch, so this reached nothing and the exit
            // dialog's Stop fell through to the pop, cancelling the commit by tearing the screen down.
            model.cancelCommit()
            advanceUntilIdle()

            model.state.value.isBusy shouldBe false
        }

    @Test
    fun `pressing back during a per-row commit asks, without letting go of the commit`() =
        runTest(dispatcher.scheduler) {
            val model = model(listOf(entry(1), entry(2)), blockOn = EntryId.Manga(1))
            advanceUntilIdle()
            model.acceptAll()
            model.commitSingle(EntryId.Manga(1), replace = true)
            advanceUntilIdle()

            // A per-row commit shows no modal, so back stays live. Answering it used to overwrite the
            // one cell that also held the commit, so dismissing left the screen idle with the
            // migration still running: a second commit could then start on top of it.
            model.showExitConfirm()
            model.state.value.isBusy shouldBe true
            model.dismissDialog()

            model.state.value.isBusy shouldBe true
        }

    @Test
    fun `a failed batch leaves no confirm dialog behind to start a second one`() = runTest(dispatcher.scheduler) {
        val model = model(listOf(entry(1), entry(2)), failFor = setOf(EntryId.Manga(2)))
        advanceUntilIdle()
        model.acceptAll()
        model.showConfirm(replace = true)
        advanceUntilIdle()

        model.commit(replace = true, flags = emptySet())
        advanceUntilIdle()

        // The screen stays open on the failed row, but with no stale dialog whose button would run
        // the whole batch again over the rows that just failed.
        model.state.value.finished shouldBe false
        model.state.value.visibleDialog.shouldBeNull()
        model.state.value.commit shouldBe EntryMigrationListViewModel.CommitActivity.Idle
    }

    @Test
    fun `a list that loads no entries says why instead of rendering nothing`() = runTest(dispatcher.scheduler) {
        val model = model(emptyList())
        advanceUntilIdle()

        // The driver breaks out before it reaches the counts, so nothing computed the reason and the
        // screen painted an empty list with no explanation.
        model.state.value.isLoading shouldBe false
        model.state.value.emptyReason shouldBe EntryMigrationListViewModel.EmptyReason.NoEntries
    }

    @Test
    fun `accept-all leaves a declined row alone`() = runTest(dispatcher.scheduler) {
        val model = model(listOf(entry(1), entry(2)))
        advanceUntilIdle()

        model.toggleAccept(EntryId.Manga(1))
        model.toggleAccept(EntryId.Manga(1))
        model.acceptAll()
        advanceUntilIdle()

        // Re-arming a target the user just handed back would migrate it on the next commit.
        model.state.value.committableCount shouldBe 1
    }

    @Test
    fun `an accepted row is committable and a declined one is not`() = runTest(dispatcher.scheduler) {
        val model = model(listOf(entry(1)))
        advanceUntilIdle()

        model.toggleAccept(EntryId.Manga(1))
        model.state.value.committableCount shouldBe 1

        model.toggleAccept(EntryId.Manga(1))
        model.state.value.committableCount shouldBe 0
    }

    @Test
    fun `a row skipped while the batch works ahead of it is not migrated`() = runTest(dispatcher.scheduler) {
        // The batch iterates a snapshot taken before it started, so it still holds a row the user has
        // since removed. The claim re-checks membership for exactly this: without it the migration
        // runs anyway and the entry the user took out is migrated behind their back.
        val entries = listOf(entry(1), entry(2), entry(3))
        val adapter = FakeMigrationFlowAdapter(entries, blockOn = EntryId.Manga(1))
        val model = EntryMigrationListViewModel(
            entryIds = entries.map { it.id.rawId },
            adapter = adapter,
            pickHandoff = MigrationPickHandoff(),
            io = dispatcher,
        )
        advanceUntilIdle()
        model.acceptAll()
        model.commit(replace = true, flags = emptySet())
        advanceUntilIdle()

        // Row 1 is hanging mid-commit, so rows 2 and 3 are still ahead of the batch in its snapshot.
        model.skipRow(EntryId.Manga(2))
        advanceUntilIdle()
        adapter.blocked.complete(Unit)
        advanceUntilIdle()

        adapter.migrated shouldBe listOf(EntryId.Manga(1), EntryId.Manga(3))
    }
}
