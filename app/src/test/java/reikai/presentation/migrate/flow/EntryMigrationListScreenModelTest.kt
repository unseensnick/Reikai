package reikai.presentation.migrate.flow

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.migrate.PickMember

/**
 * The driver, the row claim and the finish gate, which had no coverage at all until the ScreenModel
 * took its dependencies as constructor parameters: every defect in them was found by reading the
 * code in an audit, never by a test.
 */
class EntryMigrationListScreenModelTest {

    // Voyager's screenModelScope is Main-based, and the model launches its work on the dispatcher it
    // is given, so both go through one scheduler the test can advance.
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun entry(id: Long) = MigrationEntry(
        id = EntryId.Manga(id),
        title = "Entry $id",
        sourceKey = "src",
        sourceName = "Source",
        chapterCount = 1,
        latestChapter = 1.0,
        cover = null,
        payload = Any(),
    )

    private fun candidate(id: Long) = MigrationCandidate(
        sourceKey = "target",
        title = "Target $id",
        chapterCount = 2,
        latestChapter = 2.0,
        key = "target:/$id",
        handle = Any(),
    )

    /**
     * An adapter whose search always finds a target and whose migrate is recorded. [failFor] makes
     * that entry's commit throw, which is how the failure paths are reached.
     */
    private class FakeAdapter(
        private val entries: List<MigrationEntry>,
        private val failFor: Set<EntryId> = emptySet(),
        /** Migrating this entry hangs until the test cancels the batch, so the rows after it in the
         *  batch are left accepted and un-run, which is the state a cancelled batch really produces. */
        private val blockOn: EntryId? = null,
    ) : MigrationFlowAdapter {
        val migrated = mutableListOf<EntryId>()
        val blocked = CompletableDeferred<Unit>()

        override val contentType = ContentType.MANGA
        override val matchStrategy = MatchStrategy.BestTitleMatch

        override fun enabledSources() = listOf(
            MigrationSourceUi("target", "Target", "en", MigrationSourceIcon.NovelUrl(null)),
        )

        override fun savedSelection() = listOf("target")
        override fun persistSelection(keys: List<String>) = Unit
        override fun pinnedKeys(): Set<String> = emptySet()
        override suspend fun mergeGroupMembers(ids: List<Long>): List<PickMember> = emptyList()
        override fun sourceDisplayName(sourceKey: String) = sourceKey
        override fun favorites(sourceKey: String): Flow<List<MigrationFavorite>> = flowOf(emptyList())
        override fun readTuning() = MigrationTuning()
        override fun persistTuning(tuning: MigrationTuning) = Unit
        override suspend fun loadEntries(ids: List<Long>) = entries

        override suspend fun suggest(entry: MigrationEntry, sourceKey: String, tuning: MigrationTuning) =
            candidateFor(entry)

        override suspend fun candidates(entry: MigrationEntry, query: String, sourceKey: String) =
            listOf(candidateFor(entry))

        override suspend fun resolve(candidate: MigrationCandidate) = ResolvedTarget(candidate, syncedNow = true)
        override suspend fun peekCounts(candidate: MigrationCandidate): MigrationCandidate? = null
        override suspend fun storedCandidate(id: Long): MigrationCandidate? = null
        override fun savedFlags(): Set<MigrationDataFlag> = emptySet()
        override fun persistFlags(flags: Set<MigrationDataFlag>) = Unit
        override suspend fun applicableFlags(entries: List<MigrationEntry>): Set<MigrationDataFlag> = emptySet()

        override suspend fun migrate(
            entry: MigrationEntry,
            target: MigrationCandidate,
            replace: Boolean,
            flags: Set<MigrationDataFlag>,
            targetJustSynced: Boolean,
        ) {
            if (entry.id == blockOn) blocked.await()
            if (entry.id in failFor) error("migrate failed for ${entry.id}")
            migrated += entry.id
        }

        private fun candidateFor(entry: MigrationEntry) = MigrationCandidate(
            sourceKey = "target",
            title = "Target for ${entry.title}",
            chapterCount = 2,
            latestChapter = 2.0,
            key = "target:${entry.id}",
            handle = Any(),
        )
    }

    private fun model(
        entries: List<MigrationEntry>,
        failFor: Set<EntryId> = emptySet(),
        blockOn: EntryId? = null,
    ) =
        EntryMigrationListScreenModel(
            entryIds = entries.map { it.id.rawId },
            extraQuery = null,
            adapter = FakeAdapter(entries, failFor, blockOn),
            pickHandoff = MigrationPickHandoff(),
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

        model.toggleSkip(EntryId.Manga(2))
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
            model.toggleSkip(EntryId.Manga(2))
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
        model.toggleSkip(EntryId.Manga(2))
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
            model.state.value.dialog.shouldBeInstanceOf<EntryMigrationListScreenModel.Dialog.Confirm>()

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
        model.state.value.commit shouldBe EntryMigrationListScreenModel.CommitActivity.Idle
    }

    @Test
    fun `a list that loads no entries says why instead of rendering nothing`() = runTest(dispatcher.scheduler) {
        val model = model(emptyList())
        advanceUntilIdle()

        // The driver breaks out before it reaches the counts, so nothing computed the reason and the
        // screen painted an empty list with no explanation.
        model.state.value.isLoading shouldBe false
        model.state.value.emptyReason shouldBe EntryMigrationListScreenModel.EmptyReason.NoEntries
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
}
