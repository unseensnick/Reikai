package reikai.presentation.download

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.manga.model.CustomMangaInfo

class EntryDownloadQueueViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a queued series' card carries its Edit info title`(type: ContentType) = runTest {
        val custom = when (type) {
            ContentType.MANGA -> EntryId.Manga(SERIES) to CustomMangaInfo(mangaId = SERIES, title = "Mine")
            else -> EntryId.Novel(SERIES) to CustomNovelInfo(novelId = SERIES, title = "Mine")
        }
        val viewModel = main.track(
            EntryDownloadQueueViewModel(
                mangaProvider = provider(ContentType.MANGA, queued = type == ContentType.MANGA),
                novelProvider = provider(ContentType.NOVELS, queued = type == ContentType.NOVELS),
                sourcePreferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
                getEntryCustomInfo = mockk { every { subscribeAll() } returns flowOf(mapOf(custom)) },
                appScope = main.trackScope(),
            ),
        )

        viewModel.state.first { it.cards.isNotEmpty() }.cards.map { it.title } shouldBe listOf("Mine")
    }

    @Test
    fun `a card's saved slot is kept while its downloader's snapshot predates the restore`() = runTest {
        val order = savedOrderAfterPruning(novelsRestored = false)

        order shouldBe "NOVELS-$SERIES"
    }

    @Test
    fun `a card's saved slot is dropped once its downloader's restored queue lacks it`() = runTest {
        val order = savedOrderAfterPruning(novelsRestored = true)

        order shouldBe ""
    }

    @Test
    fun `a reorder undoing one still waiting on the downloader leaves the original order`() = runTest {
        val snapshot = MutableStateFlow(seriesSnapshot(listOf(SERIES, OTHER)))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val manga = mockk<MangaDownloadQueueProvider>(relaxed = true) {
            every { contentType } returns ContentType.MANGA
            every { isRunning } returns flowOf(false)
            every { snapshots } returns snapshot
            coEvery { reorderSeries(any()) } coAnswers {
                val wanted = firstArg<List<Long>>()
                if (calls++ == 0) gate.await()
                snapshot.value = seriesSnapshot(wanted)
            }
        }
        val viewModel = main.track(
            EntryDownloadQueueViewModel(
                mangaProvider = manga,
                novelProvider = provider(ContentType.NOVELS, queued = false),
                sourcePreferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
                getEntryCustomInfo = mockk { every { subscribeAll() } returns flowOf(emptyMap()) },
                appScope = main.trackScope(),
            ),
        )
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.state.first { s -> s.cards.map { it.seriesId } == listOf(SERIES, OTHER) }

        viewModel.reorder(listOf(mangaKey(OTHER), mangaKey(SERIES)))
        viewModel.reorder(listOf(mangaKey(SERIES), mangaKey(OTHER)))
        gate.complete(Unit)
        advanceUntilIdle()

        snapshot.value.chapters.map { it.seriesId } shouldBe listOf(SERIES, OTHER)
    }

    @Test
    fun `a reorder waiting on the downloader lands after the screen closes`() = runTest {
        val snapshot = MutableStateFlow(seriesSnapshot(listOf(SERIES, OTHER)))
        val gate = CompletableDeferred<Unit>()
        val manga = mockk<MangaDownloadQueueProvider>(relaxed = true) {
            every { contentType } returns ContentType.MANGA
            every { isRunning } returns flowOf(false)
            every { snapshots } returns snapshot
            coEvery { reorderSeries(any()) } coAnswers {
                gate.await()
                snapshot.value = seriesSnapshot(firstArg())
            }
        }
        val viewModel = main.track(
            EntryDownloadQueueViewModel(
                mangaProvider = manga,
                novelProvider = provider(ContentType.NOVELS, queued = false),
                sourcePreferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
                getEntryCustomInfo = mockk { every { subscribeAll() } returns flowOf(emptyMap()) },
                appScope = main.trackScope(),
            ),
        )
        val collector = launch { viewModel.state.collect {} }
        viewModel.state.first { s -> s.cards.map { it.seriesId } == listOf(SERIES, OTHER) }
        viewModel.reorder(listOf(mangaKey(OTHER), mangaKey(SERIES)))

        collector.cancel()
        viewModel.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        snapshot.value.chapters.map { it.seriesId } shouldBe listOf(OTHER, SERIES)
    }

    private fun mangaKey(seriesId: Long) = EntryDownloadCardUi.keyPrefix(ContentType.MANGA) + seriesId

    private fun seriesSnapshot(series: List<Long>) = DownloadQueueSnapshot(
        chapters = series.map { QueuedChapter(it, chapterId = it * 10, status = QueuedChapterStatus.QUEUED) },
        activeSeries = emptySet(),
        completed = emptyMap(),
        labels = series.associateWith { QueuedSeriesLabel("Title $it", "Source") },
        restored = true,
    )

    /**
     * Saves a manga slot and a novel slot with neither queued, the manga queue restored, and waits for the
     * manga slot, which nothing can bring back, to be pruned. The saved order left then.
     */
    private suspend fun TestScope.savedOrderAfterPruning(novelsRestored: Boolean): String {
        val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
        preferences.downloadQueueOrder.set("MANGA-1,NOVELS-$SERIES")
        val viewModel = main.track(
            EntryDownloadQueueViewModel(
                mangaProvider = provider(ContentType.MANGA, queued = false),
                novelProvider = provider(ContentType.NOVELS, queued = false, restored = novelsRestored),
                sourcePreferences = preferences,
                getEntryCustomInfo = mockk { every { subscribeAll() } returns flowOf(emptyMap()) },
                appScope = main.trackScope(),
            ),
        )
        backgroundScope.launch { viewModel.state.collect {} }
        return preferences.downloadQueueOrder.changes().first { "MANGA" !in it }
    }

    private inline fun <reified T : DownloadQueueProvider> provider(
        type: ContentType,
        queued: Boolean,
        restored: Boolean = true,
    ): T =
        mockk(relaxed = true) {
            every { contentType } returns type
            every { isRunning } returns flowOf(false)
            every { snapshots } returns flowOf(
                DownloadQueueSnapshot(
                    chapters = if (queued) {
                        listOf(QueuedChapter(SERIES, chapterId = 1L, status = QueuedChapterStatus.QUEUED))
                    } else {
                        emptyList()
                    },
                    activeSeries = emptySet(),
                    completed = emptyMap(),
                    labels = if (queued) mapOf(SERIES to QueuedSeriesLabel("Source title", "Source")) else emptyMap(),
                    restored = restored,
                ),
            )
        }

    private companion object {
        const val SERIES = 7L
        const val OTHER = 8L
    }
}
