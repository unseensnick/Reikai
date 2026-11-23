package reikai.presentation.stats.details

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.readinglog.EntryReadDuration
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.statsLibrary
import reikai.util.virtualDispatchers
import kotlin.time.Duration.Companion.seconds

/** A detail screen refreshes as the overview does, so a series read from it shows its new time. */
class StatsMostReadViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // The load runs on IO and the mapping on Default, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    // Main stays set, so the extension can stop the model on it before it resets Main.
    @AfterEach
    fun tearDown() = unmockkStatic(Dispatchers::class)

    private var load: suspend () -> StatsLibrary = { read(10L) }

    private fun viewModel() = main.track(
        StatsMostReadViewModel(
            loader = { load() },
            sourcePreferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
            novelRepository = mockk(),
            mangaRepository = mockk(),
        ),
    )

    @Test
    fun `a refresh shows the reading time as it is now`() = runTest {
        val model = viewModel()
        model.refresh()
        model.awaitReadMs(10L)
        load = { read(25L) }

        model.refresh()

        model.awaitReadMs(25L) shouldBe 25L
    }

    private suspend fun StatsMostReadViewModel.awaitReadMs(ms: Long): Long? =
        withTimeoutOrNull(5.seconds) { titles.first { it?.singleOrNull()?.readMs == ms } }?.single()?.readMs

    private fun read(ms: Long) = statsLibrary(mangaReadDurations = listOf(EntryReadDuration(1L, "A", ms)))
}
