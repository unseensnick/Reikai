package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.RecentsSurface
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNovelHistory
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.NovelCover
import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadCache
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.updates.NovelUpdatesViewModel
import reikai.util.virtualDispatchers
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Instant

/**
 * Each of the four feed models lays the user's custom title over its stored rows, so History, Updates
 * and Recents show (and search) a renamed entry by its custom title. RecentsMappingTest pins the row
 * types' overlay; this pins that each model applies it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsFeedCustomInfoTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // The query runs on IO, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    // Main stays set, so the extension can stop the models on it before it resets Main.
    @AfterEach
    fun tearDown() = unmockkStatic(Dispatchers::class)

    @ParameterizedTest(name = "{0}")
    @MethodSource("feeds")
    fun `a feed shows a row under the user's custom title`(feed: TitleProbe) = runTest {
        val titles = feed.build(main)

        titles.first { it.isNotEmpty() } shouldBe listOf(CUSTOM)
    }

    companion object {
        private const val CUSTOM = "Custom"
        private const val ID = 7L
        private val preferences get() = ReikaiSourcePreferences(EmittingPreferenceStore())

        @JvmStatic
        fun feeds() = listOf(mangaUpdates, novelUpdates, mangaHistory, novelHistory)

        private val mangaUpdates = TitleProbe("manga updates") { main ->
            val row = UpdatesWithRelations(
                mangaId = ID, mangaTitle = "t", chapterId = 70, chapterName = "c", scanlator = null,
                chapterUrl = "u", read = false, bookmark = false, lastPageRead = 0, pageCount = 0, sourceId = 1,
                dateFetch = Instant.fromEpochMilliseconds(1000),
                coverData = MangaCover(
                    mangaId = ID,
                    sourceId = 1,
                    isMangaFavorite = true,
                    url = null,
                    lastModified = null,
                ),
            )
            val store = EmittingPreferenceStore()
            main.track(
                UpdatesViewModel(
                    surface = RecentsSurface.UPDATES,
                    downloadManager = mockk<DownloadManager> {
                        every { statusFlow() } returns emptyFlow()
                        every { progressFlow() } returns emptyFlow()
                        every { queueState } returns MutableStateFlow(emptyList())
                        every { getQueuedDownloadsByChapterId() } returns emptyMap()
                        every { isChapterDownloaded(any(), any(), any(), any(), any()) } returns false
                    },
                    downloadCache = mockk<DownloadCache> { every { changes } returns MutableStateFlow(Unit) },
                    getUpdates = mockk<GetUpdates> {
                        every { subscribe(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                            flowOf(listOf(row))
                    },
                    getCustomMangaInfo = mockk<GetCustomMangaInfo> {
                        every { subscribeAll() } returns flowOf(listOf(CustomMangaInfo(mangaId = ID, title = CUSTOM)))
                    },
                    libraryPreferences = LibraryPreferences(store),
                    updatesPreferences = UpdatesPreferences(store),
                    reikaiSourcePreferences = preferences,
                    reikaiLibraryPreferences = ReikaiLibraryPreferences(EmittingPreferenceStore()),
                ),
            ).state.map { state -> state.items.map { it.update.mangaTitle } }
        }

        private val novelUpdates = TitleProbe("novel updates") { main ->
            val row = NovelUpdateWithRelations(
                novelId = ID, novelTitle = "t", chapterId = 70, chapterName = "c", chapterUrl = "u", read = false,
                bookmark = false, lastTextProgress = 0L, source = "s", dateFetch = Instant.fromEpochMilliseconds(1000),
                coverData = NovelCover(url = null, sourceId = null, isNovelFavorite = true, lastModified = null),
                novelUrl = "nu",
            )
            main.track(
                NovelUpdatesViewModel(
                    surface = RecentsSurface.UPDATES,
                    novelRepo = mockk<NovelRepository> {
                        every {
                            getFilteredNovelUpdatesAsFlow(any(), any(), any(), any(), any(), any(), any(), any())
                        } returns flowOf(listOf(row))
                    },
                    downloadManagerProvider = { mockk { every { queueState } returns MutableStateFlow(emptyList()) } },
                    novelDownloadCache = mockk<NovelDownloadCache>(relaxed = true) {
                        every { changes } returns MutableStateFlow(Unit)
                    },
                    sourcePreferences = preferences,
                    updatesPreferences = UpdatesPreferences(EmittingPreferenceStore()),
                    getCustomNovelInfo = mockk<GetCustomNovelInfo> {
                        every { subscribeAll() } returns flowOf(listOf(CustomNovelInfo(novelId = ID, title = CUSTOM)))
                    },
                    reikaiLibraryPreferences = ReikaiLibraryPreferences(EmittingPreferenceStore()),
                ),
            ).state.map { state -> state.items.map { it.update.novelTitle } }
        }

        private val mangaHistory = TitleProbe("manga history") { main ->
            val row = HistoryWithRelations(
                id = 1, chapterId = 70, mangaId = ID, title = "t", chapterNumber = 1.0,
                readAt = Instant.fromEpochMilliseconds(1), readDuration = 0,
                coverData = MangaCover(
                    mangaId = ID,
                    sourceId = 1,
                    isMangaFavorite = true,
                    url = null,
                    lastModified = null,
                ),
                chapterName = "c", scanlator = null, chapterUrl = "u", read = true, bookmark = false, lastPageRead = 0,
                pageCount = 0, sourceId = 1, storedTitle = "t",
            )
            main.track(
                HistoryViewModel(
                    surface = RecentsSurface.HISTORY,
                    getCustomMangaInfo = mockk<GetCustomMangaInfo> {
                        every { subscribeAll() } returns flowOf(listOf(CustomMangaInfo(mangaId = ID, title = CUSTOM)))
                    },
                    getHistory = mockk<GetHistory> {
                        every { subscribe(any(), any(), any()) } returns
                            flowOf(listOf(row))
                    },
                    removeHistory = mockk(),
                    reikaiSourcePreferences = preferences,
                ),
            ).state.map { state -> state.list.orEmpty().map { it.title } }
        }

        private val novelHistory = TitleProbe("novel history") { main ->
            val row = NovelHistoryWithRelations(
                id = 1, chapterId = 70, novelId = ID, title = "t", chapterNumber = 1.0,
                readAt = Instant.fromEpochMilliseconds(1), readDuration = 0,
                coverData = NovelCover(url = null, sourceId = null, isNovelFavorite = true, lastModified = null),
                chapterName = "c", chapterUrl = "u", read = true, bookmark = false, lastTextProgress = 0L,
                source = "s", storedTitle = "t",
            )
            main.track(
                NovelHistoryViewModel(
                    surface = RecentsSurface.HISTORY,
                    getNovelHistory = mockk<GetNovelHistory> {
                        every { subscribe(any(), any(), any()) } returns flowOf(listOf(row))
                    },
                    getCustomNovelInfo = mockk<GetCustomNovelInfo> {
                        every { subscribeAll() } returns flowOf(listOf(CustomNovelInfo(novelId = ID, title = CUSTOM)))
                    },
                    removeNovelHistory = mockk(),
                    sourcePreferences = preferences,
                ),
            ).state.map { state -> state.list.orEmpty().map { it.title } }
        }
    }
}

/** One feed model over a single stored row with a custom title, answering the titles its state shows. */
class TitleProbe(private val label: String, val build: (MainDispatcherExtension) -> Flow<List<String>>) {
    override fun toString() = label
}
