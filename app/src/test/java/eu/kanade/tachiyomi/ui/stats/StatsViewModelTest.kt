package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.statsLibrary
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers
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

    // The load runs on IO and the mapping on Default, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    // Main stays set, so the extension can stop the models on it before it resets Main.
    @AfterEach
    fun tearDown() = unmockkStatic(Dispatchers::class)

    private val store = EmittingPreferenceStore()
    private var load: suspend () -> StatsLibrary = { library(1) }

    private fun viewModel(
        mangaDownloads: Int = 0,
        novelDownloadManager: NovelDownloadManager = mockk(),
    ): StatsViewModel {
        val totalReadDuration = GetTotalReadDuration(mockk { coEvery { getTotalReadDuration() } returns 0L })
        val novelHistory = mockk<NovelHistoryRepository> { coEvery { getTotalReadDuration() } returns 0L }
        // The coEvery stubs above leave Default answering a bare mock, and the model maps on Default.
        restubDefaultDispatcher()
        return main.track(
            StatsViewModel(
                downloadManager = mockk<DownloadManager> { every { getDownloadCount() } returns mangaDownloads },
                getTotalReadDuration = totalReadDuration,
                preferences = LibraryPreferences(store),
                novelHistoryRepository = novelHistory,
                novelPreferences = NovelPreferences(store),
                sourcePreferences = ReikaiSourcePreferences(store),
                novelDownloadManager = { novelDownloadManager },
                statsLibraryLoader = { load() },
            ),
        )
    }

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
        started.await()

        (model.state.value as? StatsScreenState.Success)?.overview?.libraryMangaCount shouldBe 1
        gate.complete(library(2))
    }

    /** Two manga with four downloads, three novels with one each, which the novel manager counts. */
    @ParameterizedTest
    @CsvSource("ALL, 5, 7", "MANGA, 2, 4", "NOVELS, 3, 3")
    fun `each chip counts its own titles and downloads`(type: ContentType, titles: Int, downloads: Int) = runTest {
        ReikaiSourcePreferences(store).statsContentType.set(type)
        load = { statsLibrary(manga = (1L..2L).map(::manga), novels = (1L..3L).map(::novel)) }
        val model = viewModel(
            mangaDownloads = 4,
            novelDownloadManager = mockk { every { getDownloadCount(any()) } returns 1 },
        )

        model.refresh()

        (model.state.first { it is StatsScreenState.Success } as StatsScreenState.Success).let {
            it.overview.libraryMangaCount to
                it.chapters.downloadCount
        } shouldBe
            (titles to downloads)
    }

    private suspend fun StatsViewModel.awaitTitles(count: Int): Int? = withTimeoutOrNull(5.seconds) {
        state.first { (it as? StatsScreenState.Success)?.overview?.libraryMangaCount == count }
    }?.let { (it as StatsScreenState.Success).overview.libraryMangaCount }

    private fun library(titles: Int) = statsLibrary(manga = (1L..titles).map(::manga))

    private fun novel(id: Long) = LibraryNovel(
        novel = Novel.create().copy(id = id),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )

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
