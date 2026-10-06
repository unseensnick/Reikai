package eu.kanade.tachiyomi.data.download

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException

/**
 * Mihon's [DownloadWorker] over the real [Downloader] and [DownloadManager], all on [test]'s scheduler. Only
 * the network, WorkManager, the disk and the source are faked; a fetch hangs until [failFetchInFlight].
 * The notifier is a stub recording into [events] unless [notifierFor] builds a real one.
 */
class DownloadWorkerFixture(
    private val test: TestScope,
    private val notifierFor: ((Context) -> DownloadNotifier)? = null,
) : AutoCloseable {

    var network = ONLINE
    val events = mutableListOf<String>()
    var fetches = 0
        private set
    val restored = CompletableDeferred<List<Download>>()

    private var inFlight = CompletableDeferred<Nothing>()
    private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Manga")
    private val chapter = Chapter.create().copy(id = 10L, mangaId = manga.id, url = "u10", name = "Ch 10")
    private val source = mockk<HttpSource> {
        every { id } returns manga.source
        coEvery { getPageList(any()) } coAnswers {
            fetches++
            inFlight = CompletableDeferred()
            inFlight.await()
        }
    }
    private val folder: UniFile = mockk(relaxed = true) {
        every { createDirectory(any()) } returns this
        every { findFile(any()) } returns null
    }
    private val app = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class))
    private val provider = DownloadProvider(
        app,
        mockk<StorageManager> { every { getDownloadsDirectory() } returns folder },
        LibraryPreferences(InMemoryPreferenceStore()),
    )

    // The downloader waits on its parallel-source setting, which an in-memory preference never emits.
    val downloadPreferences = DownloadPreferences(EmittingPreferenceStore())
    private val sourceManager = mockk<SourceManager>().also { coEvery { it.get(manga.source) } returns source }

    init {
        // Mihon's Downloader restores its saved queue on the main dispatcher as it is built, and runs
        // its downloads on IO, which here is the test's own clock.
        Dispatchers.setMain(UnconfinedTestDispatcher(test.testScheduler))
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns StandardTestDispatcher(test.testScheduler)
        mockkObject(DownloadWorker.Companion)
        every { DownloadWorker.start(any()) } answers { events += "enqueued" }
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } answers { network }
        mockkStatic(WORKER_EXTENSIONS)
        coEvery { any<CoroutineWorker>().setForegroundSafely() } answers { events += "foreground" }
        every { app.applicationContext } returns app
    }

    val downloader by lazy {
        Downloader(
            context = app,
            provider = provider,
            cache = mockk(relaxed = true),
            sourceManager = sourceManager,
            chapterCache = mockk(),
            downloadPreferences = downloadPreferences,
            xml = mockk(),
            getCategories = mockk(),
            getTracks = mockk(),
            store = mockk<DownloadStore>(relaxed = true) { coEvery { restore() } coAnswers { restored.await() } },
            notifier = notifierFor?.invoke(app) ?: mockk<DownloadNotifier>(relaxed = true) {
                every { onNetworkPause(any()) } answers { events += "paused" }
            },
        )
    }

    val downloadManager by lazy {
        DownloadManager(
            context = app,
            provider = provider,
            cache = mockk(relaxed = true),
            getCategories = mockk(),
            sourceManager = sourceManager,
            downloadPreferences = downloadPreferences,
            getManga = mockk(),
            getChapter = mockk(),
            downloader = downloader,
            pendingDeleter = mockk(),
            sourceTitles = mockk(),
        )
    }

    /** Queues one chapter without starting anything, the saved queue having restored empty. */
    suspend fun queue() {
        restored.complete(emptyList())
        downloadManager.downloadChapters(manga, listOf(chapter), autoStart = false)
    }

    fun restoredDownload() = Download(source, manga, chapter)

    /** Runs the worker as WorkManager would. */
    fun startWorker(): Deferred<ListenableWorker.Result> {
        val graph = mockk<AppGraph>()
        // Stubbed outside a mockk block, where the names below bind to the graph's own accessors.
        every { graph.inject(any<DownloadWorker>()) } answers {
            firstArg<DownloadWorker>().setField("downloader", downloader)
            firstArg<DownloadWorker>().setField("downloadPreferences", downloadPreferences)
        }
        @Suppress("UNCHECKED_CAST")
        every { (app as GraphProvider<AppGraph>).graph } returns graph
        return test.backgroundScope.async { DownloadWorker(app, mockk(relaxed = true)).doWork() }
    }

    /** Fails the fetch in flight, as a dropped connection eventually does; nothing once it was cancelled. */
    fun failFetchInFlight() {
        inFlight.completeExceptionally(IOException("connection lost"))
    }

    override fun close() {
        downloader.pause()
        Dispatchers.resetMain()
        unmockkStatic(Dispatchers::class)
        unmockkObject(DownloadWorker.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(WORKER_EXTENSIONS)
    }

    private fun DownloadWorker.setField(name: String, value: Any) {
        DownloadWorker::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    companion object {
        val ONLINE = NetworkState(isConnected = true, isValidated = true, isWifi = true)
        val MOBILE = NetworkState(isConnected = true, isValidated = true, isWifi = false)
        val OFFLINE = NetworkState(isConnected = false, isValidated = false, isWifi = false)

        private const val WORKER_EXTENSIONS = "eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt"
    }
}
