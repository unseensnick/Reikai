package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.statsLibrary
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.seconds

/** As a tab the model lives all session, so each refresh must show the library as it is then. */
class StatsViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val store = EmittingPreferenceStore()
    private var load: suspend () -> StatsLibrary = { library(1) }

    private fun viewModel() = main.track(
        StatsViewModel(
            downloadManager = mockk<DownloadManager> { every { getDownloadCount() } returns 0 },
            getTotalReadDuration = GetTotalReadDuration(mockk { coEvery { getTotalReadDuration() } returns 0L }),
            preferences = LibraryPreferences(store),
            novelHistoryRepository = mockk<NovelHistoryRepository> { coEvery { getTotalReadDuration() } returns 0L },
            novelPreferences = NovelPreferences(store),
            sourcePreferences = ReikaiSourcePreferences(store),
            novelDownloadManager = { mockk<NovelDownloadManager>() },
            statsLibraryLoader = { load() },
            novelRepository = mockk(),
        ),
    )

    @Test
    fun `a refresh shows the library as it is now`() = runTest {
        val model = viewModel()
        model.refresh()
        model.awaitTitles(1)
        load = { library(2) }

        model.refresh()

        model.awaitTitles(2) shouldBe 2
    }

    @Test
    fun `a refresh keeps the last figures on screen while it loads`() = runTest {
        val model = viewModel()
        model.refresh()
        model.awaitTitles(1)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<StatsLibrary>()
        load = {
            started.complete(Unit)
            gate.await()
        }

        model.refresh()
        withContext(Dispatchers.Default) { withTimeoutOrNull(5.seconds) { started.await() } }

        (model.state.value as? StatsScreenState.Success)?.overview?.libraryMangaCount shouldBe 1
        gate.complete(library(2))
    }

    private suspend fun StatsViewModel.awaitTitles(count: Int): Int? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(5.seconds) {
            state.first { (it as? StatsScreenState.Success)?.overview?.libraryMangaCount == count }
        }?.let { (it as StatsScreenState.Success).overview.libraryMangaCount }
    }

    private fun library(titles: Int) = statsLibrary(manga = (1L..titles).map(::manga))

    private fun manga(id: Long) = LibraryManga(
        manga = Manga.create().copy(id = id),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )
}
