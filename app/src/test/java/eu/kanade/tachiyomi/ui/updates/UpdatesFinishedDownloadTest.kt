package eu.kanade.tachiyomi.ui.updates

import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.category.RecentsSurface
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.virtualDispatchers
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Instant

/**
 * A finished chapter leaves the queue, and a status tick can arrive after it has, so the queue clearing
 * the row's override first and the late tick setting it again left the row showing as downloading.
 */
class UpdatesFinishedDownloadTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // The feed runs on IO, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    // Main stays set, so the extension can stop the model on it before it resets Main.
    @AfterEach
    fun tearDown() = unmockkStatic(Dispatchers::class)

    private val queue = MutableStateFlow<List<Download>>(emptyList())
    private val ticks = MutableSharedFlow<Download>(extraBufferCapacity = 8)
    private val onDisk = mutableSetOf<Long>()

    private val downloadManager = mockk<DownloadManager> {
        every { statusFlow() } returns ticks
        every { progressFlow() } returns emptyFlow()
        every { queueState } returns queue
        every { getQueuedDownloadOrNull(any()) } answers { queue.value.find { it.chapter.id == firstArg() } }
        every { getQueuedDownloadsByChapterId() } answers { queue.value.associateBy { it.chapter.id } }
        every { isChapterDownloaded(any(), any(), any(), any(), any()) } answers {
            firstArg<String>().toLong() in onDisk
        }
    }

    @Test
    fun `a status tick arriving after a chapter finished does not show it as downloading`() = runTest {
        val model = main.track(updatesModel(listOf(update(7L), update(8L))))
        // On the test scope, not its background one, so advanceUntilIdle runs the feed it drives.
        val screen = launch { model.state.collect { } }
        val finishing = download(7L).apply { status = Download.State.DOWNLOADING }
        val next = download(8L).apply { status = Download.State.QUEUE }
        queue.value = listOf(finishing, next)
        awaitState(model, 7L, Download.State.DOWNLOADING) { ticks.emit(finishing) }

        onDisk += 7L
        queue.value = listOf(next)
        awaitState(model, 7L, Download.State.DOWNLOADED) {}
        ticks.emit(finishing)
        // The ticks are collected in order, so once the later one shows the late one has landed too.
        next.status = Download.State.DOWNLOADING
        awaitState(model, 8L, Download.State.DOWNLOADING) { ticks.emit(next) }

        val shown = model.stateOf(7L)
        screen.cancel()

        shown shouldBe Download.State.DOWNLOADED
    }

    private suspend fun TestScope.awaitState(
        model: UpdatesViewModel,
        chapterId: Long,
        expected: Download.State,
        nudge: suspend () -> Unit,
    ) {
        // Settled first, so a tick the nudge emits has the feed subscribed to receive it.
        advanceUntilIdle()
        nudge()
        advanceUntilIdle()
        check(model.stateOf(chapterId) == expected) { "Chapter $chapterId never showed $expected" }
    }

    private fun UpdatesViewModel.stateOf(chapterId: Long): Download.State? =
        state.value.items.find { it.update.chapterId == chapterId }?.downloadStateProvider?.invoke()

    private fun updatesModel(updates: List<UpdatesWithRelations>): UpdatesViewModel {
        val store = EmittingPreferenceStore()
        return UpdatesViewModel(
            surface = RecentsSurface.UPDATES,
            downloadManager = downloadManager,
            downloadCache = mockk<DownloadCache> { every { changes } returns MutableStateFlow(Unit) },
            getUpdates = mockk<GetUpdates> {
                every { subscribe(any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf(updates)
            },
            getCustomMangaInfo = mockk<GetCustomMangaInfo> { every { subscribeAll() } returns flowOf(emptyList()) },
            libraryPreferences = LibraryPreferences(store),
            updatesPreferences = UpdatesPreferences(store),
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            reikaiLibraryPreferences = ReikaiLibraryPreferences(store),
        )
    }

    private fun download(chapterId: Long) = Download(
        source = mockk(),
        manga = Manga.create().copy(id = 1L, title = "t"),
        chapter = Chapter.create().copy(id = chapterId, mangaId = 1L, name = "$chapterId"),
    )

    private fun update(chapterId: Long) = UpdatesWithRelations(
        mangaId = 1L,
        mangaTitle = "t",
        chapterId = chapterId,
        chapterName = "$chapterId",
        scanlator = null,
        chapterUrl = "u$chapterId",
        read = false,
        bookmark = false,
        lastPageRead = 0,
        pageCount = 0,
        sourceId = 1L,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        coverData = MangaCover(1L, 1L, true, null, null),
    )
}
