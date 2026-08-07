package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The single-entry route. It runs the same search seam the batch list does, and the two are the two
 * halves of one option: the extra query was dropped on both, and each route needs its own guard.
 */
class EntryMigrationSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun model(adapter: FakeMigrationFlowAdapter, extraQuery: String?) = EntryMigrationSearchViewModel(
        entryId = 1L,
        adapter = adapter,
        pickHandoff = MigrationPickHandoff(),
        extraQuery = extraQuery,
        io = dispatcher,
    )

    @Test
    fun `the opening search carries the extra query`() = runTest(dispatcher.scheduler) {
        val adapter = FakeMigrationFlowAdapter(listOf(migrationEntry(1)))

        model(adapter, extraQuery = "vol 2")
        advanceUntilIdle()

        adapter.candidateQueries shouldBe listOf("Entry 1 vol 2")
    }

    @Test
    fun `a re-search from the toolbar carries it too`() = runTest(dispatcher.scheduler) {
        val adapter = FakeMigrationFlowAdapter(listOf(migrationEntry(1)))
        val model = model(adapter, extraQuery = "vol 2")
        advanceUntilIdle()

        model.search("another title")
        advanceUntilIdle()

        adapter.candidateQueries.last() shouldBe "another title vol 2"
    }

    @Test
    fun `with no extra query the search is the query alone`() = runTest(dispatcher.scheduler) {
        val adapter = FakeMigrationFlowAdapter(listOf(migrationEntry(1)))

        model(adapter, extraQuery = null)
        advanceUntilIdle()

        adapter.candidateQueries shouldBe listOf("Entry 1")
    }
}
