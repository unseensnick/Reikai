package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.RecentsSurface
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.NovelCover
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadCache
import reikai.novel.local.LocalNovelSource
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.updates.NovelUpdatesViewModel
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.source.local.LocalSource
import kotlin.time.Instant

/**
 * A local series' chapter in Updates is its own file: it survives the Downloaded filter, reads as
 * downloaded and draws no download control, for both content types (recents.md Decisions). The history
 * lanes take the same [recentsDownloadUi] and the [reikai.domain.manga.isOnDevice] kernels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalRecentsDownloadConformanceTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @ParameterizedTest(name = "{0}")
    @MethodSource("feeds")
    fun `a local row is kept by the Downloaded filter as downloaded`(feed: LocalRowProbe) = runTest {
        rowsOf(feed).map { it.state() } shouldBe listOf(Download.State.DOWNLOADED)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("feeds")
    fun `a local row draws no download control`(feed: LocalRowProbe) = runTest {
        rowsOf(feed).map { it.offered() } shouldBe listOf(false)
    }

    // The query runs on the IO dispatcher, which virtual time does not reach.
    private suspend fun rowsOf(feed: LocalRowProbe) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { feed.build(main).first { it != null } }
    }.orEmpty()

    companion object {
        private const val ID = 7L
        private val at = Instant.fromEpochMilliseconds(1000)

        @JvmStatic
        fun feeds() = listOf(manga, novel)

        private fun downloadedOnly() = EmittingPreferenceStore().also {
            UpdatesPreferences(it).filterDownloaded.set(TriState.ENABLED_IS)
        }

        private val manga = LocalRowProbe("manga") { main ->
            val row = UpdatesWithRelations(
                mangaId = ID, mangaTitle = "t", chapterId = 70, chapterName = "c", scanlator = null,
                chapterUrl = "u", read = false, bookmark = false, lastPageRead = 0, pageCount = 0,
                sourceId = LocalSource.ID, dateFetch = at,
                coverData = MangaCover(ID, LocalSource.ID, isMangaFavorite = true, url = null, lastModified = null),
            )
            val store = downloadedOnly()
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
                        every { subscribe(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(listOf(row))
                    },
                    getCustomMangaInfo = mockk<GetCustomMangaInfo> {
                        every { subscribeAll() } returns
                            flowOf(emptyList())
                    },
                    libraryPreferences = LibraryPreferences(store),
                    updatesPreferences = UpdatesPreferences(store),
                    reikaiSourcePreferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
                ),
            ).state.map { state ->
                state.items.takeUnless { state.isLoading }?.map { item ->
                    mangaDownloadUi(item.asRow(EntryId.Manga(ID), item.update.chapterId))!!
                }
            }
        }

        private val novel = LocalRowProbe("novel") { main ->
            val row = NovelUpdateWithRelations(
                novelId = ID, novelTitle = "t", chapterId = 70, chapterName = "c", chapterUrl = "u", read = false,
                bookmark = false, lastTextProgress = 0L, source = LocalNovelSource.ID, dateFetch = at,
                coverData = NovelCover(url = null, sourceId = null, isNovelFavorite = true, lastModified = null),
                novelUrl = "nu",
            )
            main.track(
                NovelUpdatesViewModel(
                    surface = RecentsSurface.UPDATES,
                    novelRepo = mockk<NovelRepository> {
                        every {
                            getFilteredNovelUpdatesAsFlow(any(), any(), any(), any(), any(), any(), any())
                        } returns flowOf(listOf(row))
                    },
                    downloadManagerProvider = { mockk { every { queueState } returns MutableStateFlow(emptyList()) } },
                    novelDownloadCache = mockk<NovelDownloadCache>(relaxed = true) {
                        every { changes } returns MutableStateFlow(Unit)
                    },
                    sourcePreferences = ReikaiSourcePreferences(EmittingPreferenceStore()),
                    updatesPreferences = UpdatesPreferences(downloadedOnly()),
                    getCustomNovelInfo = mockk<GetCustomNovelInfo> {
                        every { subscribeAll() } returns
                            flowOf(emptyList())
                    },
                ),
            ).state.map { state ->
                state.items.takeUnless { state.isLoading }?.map { item ->
                    novelDownloadUi(item.asRow(EntryId.Novel(ID), item.update.chapterId))!!
                }
            }
        }

        private fun Any.asRow(entry: EntryId, chapterId: Long) =
            RecentsItem(entry, at, RecentsLane.Updated(ChapterRef(entry, chapterId)), payload = this)
    }
}

/** One updates model over a single local row with Downloaded filtered in, answering its rows' controls. */
class LocalRowProbe(private val label: String, val build: (MainDispatcherExtension) -> Flow<List<RecentsDownloadUi>?>) {
    override fun toString() = label
}
