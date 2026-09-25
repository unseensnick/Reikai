package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId

/**
 * The picker's selection. Back and the up arrow clear it, as Mihon's picker does, where they used to
 * leave the screen and drop it silently; Continue keeps it, so backing out of config can adjust it.
 */
class EntryMigrationFavoritesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `clearing the selection leaves nothing selected`() = runTest(dispatcher) {
        val favorites = listOf(1L, 2L).map { MigrationFavorite(EntryId.Manga(it), "t$it", null, Unit) }
        val viewModel =
            EntryMigrationFavoritesViewModel(FakeMigrationFlowAdapter(emptyList(), favorites = favorites), "src")
        backgroundScope.launch { viewModel.state.collect {} }
        // The state is built on the IO dispatcher, which virtual time does not reach, so each step
        // awaits the emission it needs rather than advancing the clock.
        viewModel.state.first { it.entries.size == 2 }
        viewModel.selectAll()
        viewModel.state.first { it.selected.size == 2 }

        viewModel.clearSelection()

        viewModel.state.first { it.selected.size != 2 }.selected shouldBe emptySet()
    }
}
