package reikai.presentation.stats.details

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.readinglog.EntryReadDuration
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.statsLibrary
import kotlin.time.Duration.Companion.seconds

/** A detail screen refreshes as the overview does, so a series read from it shows its new time. */
class StatsMostReadViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

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

    private suspend fun StatsMostReadViewModel.awaitReadMs(ms: Long): Long? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(5.seconds) { titles.first { it?.singleOrNull()?.readMs == ms } }?.single()?.readMs
    }

    private fun read(ms: Long) = statsLibrary(mangaReadDurations = listOf(EntryReadDuration(1L, "A", ms)))
}
