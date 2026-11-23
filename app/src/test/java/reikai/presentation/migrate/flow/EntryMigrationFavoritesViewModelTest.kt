package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.entry.EntryId
import reikai.presentation.MainDispatcherExtension
import reikai.util.virtualDispatchers
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The picker's selection. Back and the up arrow clear it, as Mihon's picker does, where they used to
 * leave the screen and drop it silently; Continue keeps it, so backing out of config can adjust it.
 */
class EntryMigrationFavoritesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension { dispatcher }

    // The state is built on IO, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(dispatcher)
    }

    // Main stays set, so the extension can stop the models on it before it resets Main.
    @AfterEach
    fun tearDown() = unmockkStatic(Dispatchers::class)

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

    @Test
    fun `the favorites list in title order, ignoring case`() = runTest(dispatcher) {
        val favorites = listOf("beta", "Alpha").mapIndexed { i, title ->
            MigrationFavorite(EntryId.Manga(i + 1L), title, null, MigrationPayload.OfManga(Manga.create()))
        }
        val viewModel = main.track(
            EntryMigrationFavoritesViewModel(FakeMigrationFlowAdapter(emptyList(), favorites = favorites), "src"),
        )
        backgroundScope.launch { viewModel.state.collect {} }

        viewModel.state.first { it.entries.size == 2 }.entries.map { it.title } shouldBe listOf("Alpha", "beta")
    }

    // Navigation 3 drops a covered page from composition, so a page pushed over the list stops its collector.
    @Test
    fun `coming back to the list after it stopped collecting never shows the spinner again`() =
        runTest(dispatcher) {
            val fake = FakeMigrationFlowAdapter(emptyList(), favorites = favorites(2))
            var reads = 0
            // A second read never answers, so a list that read again would stay on its spinner.
            val adapter = object : MigrationFlowAdapter by fake {
                override fun favorites(sourceKey: String) =
                    if (reads++ == 0) fake.favorites(sourceKey) else flow { awaitCancellation() }
            }
            val viewModel = main.track(EntryMigrationFavoritesViewModel(adapter, "src"))
            val screen = backgroundScope.launch { viewModel.state.collect {} }
            viewModel.state.first { it.entries.size == 2 }
            screen.cancel()
            advanceUntilIdle()
            // Past the share's five-second stop.
            advanceTimeBy(5.5.seconds)
            runCurrent()

            val seen = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { seen += it.isLoading }
            }
            advanceTimeBy(200.milliseconds)
            runCurrent()

            seen shouldBe listOf(false)
        }

    private fun favorites(count: Int) = (1L..count).map {
        MigrationFavorite(EntryId.Manga(it), "t$it", null, MigrationPayload.OfManga(Manga.create()))
    }

    private suspend fun TestScope.loaded(count: Int): EntryMigrationFavoritesViewModel {
        val viewModel = main.track(
            EntryMigrationFavoritesViewModel(
                FakeMigrationFlowAdapter(emptyList(), favorites = favorites(count)),
                "src",
            ),
        )
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.state.first { it.entries.size == count }
        return viewModel
    }
}
