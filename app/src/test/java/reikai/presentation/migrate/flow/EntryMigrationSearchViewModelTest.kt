package reikai.presentation.migrate.flow

import eu.kanade.domain.source.service.SourcePreferences
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
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * The single-entry route. It runs the same search seam the batch list does, and the two are the two
 * halves of one option: the extra query was dropped on both, and each route needs its own guard.
 */
class EntryMigrationSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val sourcePreferences = SourcePreferences(EmittingPreferenceStore())

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun model(
        adapter: FakeMigrationFlowAdapter = FakeMigrationFlowAdapter(listOf(migrationEntry(1))),
        extraQuery: String? = null,
    ) = EntryMigrationSearchViewModel(
        entryId = 1L,
        adapter = adapter,
        pickHandoff = MigrationPickHandoff(),
        sourcePreferences = sourcePreferences,
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

    @Test
    fun `the has-results filter opens as the user last left it`() = runTest(dispatcher.scheduler) {
        sourcePreferences.globalSearchFilterState.set(true)

        val model = model()
        advanceUntilIdle()

        model.state.value.onlyShowHasResults shouldBe true
    }

    @Test
    fun `toggling the has-results filter writes the shared preference`() = runTest(dispatcher.scheduler) {
        val model = model()
        advanceUntilIdle()

        model.toggleOnlyResults()

        sourcePreferences.globalSearchFilterState.get() shouldBe true
    }
}
