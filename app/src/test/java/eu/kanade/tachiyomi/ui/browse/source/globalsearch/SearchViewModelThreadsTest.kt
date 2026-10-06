package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import io.kotest.matchers.collections.shouldBeEmpty
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

/** Each global search used to start a thread pool nothing shut down (mihonapp/mihon#4036). */
class SearchViewModelThreadsTest {

    private val source = mockk<Source> {
        every { id } returns 1L
        every { getFilterList() } returns mockk(relaxed = true)
        coEvery { getSearchManga(any(), any(), any()) } returns MangasPage(emptyList(), false)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a closed search leaves no thread of its own running`() = runTest {
        val before = liveAppThreads()

        repeat(3) {
            val viewModel = GlobalSearchViewModel(
                initialExtensionFilter = null,
                sourcePreferences = SourcePreferences(InMemoryPreferenceStore()),
                sourceManager = mockk(),
                extensionManager = mockk(),
                networkToLocalManga = mockk<NetworkToLocalManga> {
                    coEvery { this@mockk(any<List<Manga>>()) } returns emptyList()
                },
                getManga = mockk(),
                mangaLibraryAdder = mockk(),
            )
            ViewModelStore().apply { put("search", viewModel) }.run {
                viewModel.searchSource(source, "query")
                clear()
            }
        }

        (liveAppThreads() - before).shouldBeEmpty()
    }

    /** A non-daemon thread keeps the process alive; the shared dispatchers' workers are daemons. */
    private fun liveAppThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && !it.isDaemon }.toSet()
}
