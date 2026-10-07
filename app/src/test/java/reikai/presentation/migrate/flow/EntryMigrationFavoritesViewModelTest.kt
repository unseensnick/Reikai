package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.entry.EntryId
import reikai.presentation.MainDispatcherExtension
import tachiyomi.domain.manga.model.Manga

/**
 * The picker's selection. Back and the up arrow clear it, as Mihon's picker does, where they used to
 * leave the screen and drop it silently; Continue keeps it, so backing out of config can adjust it.
 */
class EntryMigrationFavoritesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension { dispatcher }

    @Test
    fun `clearing the selection leaves nothing selected`() = runTest(dispatcher) {
        val viewModel = loaded(2)
        viewModel.selectAll()
        viewModel.state.first { it.selected.size == 2 }

        viewModel.clearSelection()

        viewModel.state.first { it.selected.size != 2 }.selected shouldBe emptySet()
    }

    @Test
    fun `a long press selects every entry from the one last tapped`() = runTest(dispatcher) {
        val viewModel = loaded(4)
        viewModel.toggle(EntryId.Manga(1L))

        viewModel.rangeSelect(EntryId.Manga(3L))

        viewModel.state.first { it.selected.size > 1 }.selected shouldBe (1L..3L).map { EntryId.Manga(it) }.toSet()
    }

    @Test
    fun `a long press on a selected entry drops it`() = runTest(dispatcher) {
        val viewModel = loaded(2)
        viewModel.selectAll()
        viewModel.state.first { it.selected.size == 2 }

        viewModel.rangeSelect(EntryId.Manga(2L))

        viewModel.state.first { it.selected.size != 2 }.selected shouldBe setOf(EntryId.Manga(1L))
    }

    @Test
    fun `inverting selects exactly what was not selected`() = runTest(dispatcher) {
        val viewModel = loaded(3)
        viewModel.toggle(EntryId.Manga(1L))
        viewModel.state.first { it.selected.size == 1 }

        viewModel.invertSelection()

        viewModel.state.first { it.selected.size == 2 }.selected shouldBe setOf(EntryId.Manga(2L), EntryId.Manga(3L))
    }

    private suspend fun TestScope.loaded(count: Int): EntryMigrationFavoritesViewModel {
        val favorites = (1L..count).map {
            MigrationFavorite(EntryId.Manga(it), "t$it", null, MigrationPayload.OfManga(Manga.create()))
        }
        val viewModel = main.track(
            EntryMigrationFavoritesViewModel(FakeMigrationFlowAdapter(emptyList(), favorites = favorites), "src"),
        )
        backgroundScope.launch { viewModel.state.collect {} }
        // The state is built on the IO dispatcher, which virtual time does not reach, so each step
        // awaits the emission it needs rather than advancing the clock.
        viewModel.state.first { it.entries.size == count }
        return viewModel
    }
}
