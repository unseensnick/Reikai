package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.RecentsSurface
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNovelHistory
import reikai.domain.novel.interactor.RemoveNovelHistory
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadCache
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.updates.NovelUpdatesViewModel
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.service.UpdatesPreferences

/**
 * Every feed model reads the category selection of the surface that built it. The combined tab builds
 * the same four models the two separate tabs do, and while each read the selection of the tab it was
 * written for, a category excluded on the combined tab reached only its newly added lane. Each surface
 * here holds a different excluded category, so a model reading the wrong one names the wrong id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsFeedSurfaceTest {

    private val preferences = ReikaiSourcePreferences(EmittingPreferenceStore()).apply {
        updatesFilterCategories.set(true)
        updatesFilterCategoriesExclude.set(setOf("5"))
        historyFilterCategories.set(true)
        historyFilterCategoriesExclude.set(setOf("6"))
        recentsFilterCategories.set(true)
        recentsFilterCategoriesExclude.set(setOf("3"))
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("feeds")
    fun `a feed model reads the category selection of the surface that built it`(
        feed: FeedProbe,
        surface: RecentsSurface,
        excluded: Long,
    ) = runTest {
        val asked = mutableListOf<List<Long>>()
        val state = feed.build(surface, preferences) { asked += it }
        backgroundScope.launch { state.collect { } }

        // The query runs on the IO dispatcher, which virtual time does not reach.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (asked.isEmpty()) delay(10) } }

        asked.first() shouldBe listOf(excluded)
    }

    companion object {
        @JvmStatic
        fun feeds(): List<Arguments> = listOf(
            Arguments.of(mangaUpdates, RecentsSurface.UPDATES, 5L),
            Arguments.of(mangaUpdates, RecentsSurface.RECENTS, 3L),
            Arguments.of(novelUpdates, RecentsSurface.UPDATES, 5L),
            Arguments.of(novelUpdates, RecentsSurface.RECENTS, 3L),
            Arguments.of(mangaHistory, RecentsSurface.HISTORY, 6L),
            Arguments.of(mangaHistory, RecentsSurface.RECENTS, 3L),
            Arguments.of(novelHistory, RecentsSurface.HISTORY, 6L),
            Arguments.of(novelHistory, RecentsSurface.RECENTS, 3L),
        )

        private val mangaUpdates = FeedProbe("manga updates") { surface, preferences, asked ->
            val downloadManager = mockk<DownloadManager> {
                every { statusFlow() } returns emptyFlow()
                every { progressFlow() } returns emptyFlow()
                every { queueState } returns MutableStateFlow(emptyList())
            }
            val getUpdates = mockk<GetUpdates> {
                every { subscribe(any(), any(), any(), any(), any(), any(), any()) } answers {
                    asked(arg(6))
                    flowOf(emptyList())
                }
            }
            val store = EmittingPreferenceStore()
            UpdatesViewModel(
                surface = surface,
                downloadManager = downloadManager,
                downloadCache = mockk<DownloadCache> { every { changes } returns MutableStateFlow(Unit) },
                getUpdates = getUpdates,
                getCustomMangaInfo = mockk<GetCustomMangaInfo> { every { subscribeAll() } returns flowOf(emptyList()) },
                libraryPreferences = LibraryPreferences(store),
                updatesPreferences = UpdatesPreferences(store),
                reikaiSourcePreferences = preferences,
            ).state
        }

        private val novelUpdates = FeedProbe("novel updates") { surface, preferences, asked ->
            val repository = mockk<NovelRepository> {
                every { getFilteredNovelUpdatesAsFlow(any(), any(), any(), any(), any(), any(), any()) } answers {
                    asked(arg(6))
                    flowOf(emptyList())
                }
            }
            NovelUpdatesViewModel(
                surface = surface,
                novelRepo = repository,
                downloadManagerProvider = { mockk { every { queueState } returns MutableStateFlow(emptyList()) } },
                novelDownloadCache = mockk<NovelDownloadCache> { every { changes } returns MutableStateFlow(Unit) },
                sourcePreferences = preferences,
                updatesPreferences = UpdatesPreferences(EmittingPreferenceStore()),
                getCustomNovelInfo = mockk<GetCustomNovelInfo> { every { subscribeAll() } returns flowOf(emptyList()) },
            ).state
        }

        private val mangaHistory = FeedProbe("manga history") { surface, preferences, asked ->
            val getHistory = mockk<GetHistory> {
                every { subscribe(any(), any(), any()) } answers {
                    asked(arg(2))
                    flowOf(emptyList())
                }
            }
            HistoryViewModel(
                surface = surface,
                getCustomMangaInfo = mockk<GetCustomMangaInfo> { every { subscribeAll() } returns flowOf(emptyList()) },
                getHistory = getHistory,
                removeHistory = mockk<RemoveHistory>(),
                reikaiSourcePreferences = preferences,
            ).state
        }

        private val novelHistory = FeedProbe("novel history") { surface, preferences, asked ->
            val getNovelHistory = mockk<GetNovelHistory> {
                every { subscribe(any(), any(), any()) } answers {
                    asked(arg(2))
                    flowOf(emptyList())
                }
            }
            NovelHistoryViewModel(
                surface = surface,
                getNovelHistory = getNovelHistory,
                getCustomNovelInfo = mockk<GetCustomNovelInfo> { every { subscribeAll() } returns flowOf(emptyList()) },
                removeNovelHistory = mockk<RemoveNovelHistory>(),
                sourcePreferences = preferences,
            ).state
        }
    }
}

/**
 * One feed model, built over a stubbed query that reports the excluded categories it was asked with.
 * [build] answers the model's state, which is what opens the query when collected.
 */
class FeedProbe(
    private val label: String,
    val build: (RecentsSurface, ReikaiSourcePreferences, (List<Long>) -> Unit) -> Flow<*>,
) {
    override fun toString() = label
}
