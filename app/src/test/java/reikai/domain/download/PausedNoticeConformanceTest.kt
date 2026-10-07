package reikai.domain.download

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadNotifier
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.MOBILE
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.OFFLINE
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.ONLINE
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadWorker
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import java.io.IOException

/**
 * A user pause leaves one Paused notice that outlives its worker, and resuming without a network
 * replaces it with the waiting worker's own rather than showing a second, over both real engines.
 */
class PausedNoticeConformanceTest {

    // Closed after runTest, which cancels the waiting worker on its way out; that still posts.
    private fun conformance(half: PausedNoticeHalf, body: suspend TestScope.() -> Unit) = half.use {
        runTest { body() }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a pause leaves a paused notice once its worker ends`(half: PausedNoticeHalf) = conformance(half) {
        half.start(this)
        tick()

        half.pause(this)

        half.notices shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `resuming offline after a pause leaves one paused notice`(half: PausedNoticeHalf) = conformance(half) {
        half.start(this)
        tick()
        half.pause(this)

        half.resumeOffline(this)

        half.notices shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue waiting for a network says why in its notice`(half: PausedNoticeHalf) = conformance(half) {
        half.start(this)
        tick()
        half.pause(this)

        half.resumeOffline(this)

        half.noticeTexts() shouldBe listOf(NO_NETWORK)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a waiting queue's notice says why it waits now`(half: PausedNoticeHalf) = conformance(half) {
        half.network = MOBILE
        half.start(this)
        tick()

        half.network = OFFLINE
        tick()

        half.noticeTexts() shouldBe listOf(NO_NETWORK)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue started off the network says why from its first notice`(half: PausedNoticeHalf) = conformance(half) {
        half.network = OFFLINE
        half.start(this)
        tick()

        // WorkManager's foreground service can post the worker's first notice after the engine's own.
        half.postForegroundNotice()

        half.noticeTexts() shouldBe listOf(NO_NETWORK)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue cut off after it started keeps its paused notice over the worker's first`(half: PausedNoticeHalf) =
        conformance(half) {
            half.start(this)
            tick()

            half.network = OFFLINE
            tick()
            // The service can post the worker's first notice, built while it was online, after the pause.
            half.postForegroundNotice()
            tick()

            half.noticeTexts() shouldBe listOf(NO_NETWORK)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a waiting queue's new reason survives the service posting its last request`(half: PausedNoticeHalf) =
        conformance(half) {
            half.network = OFFLINE
            half.start(this)
            tick()

            half.network = MOBILE
            tick()
            // Another worker going foreground makes the service post this worker's last request again.
            half.postForegroundNotice()

            half.noticeTexts() shouldBe listOf(NO_WIFI)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue waiting for a network shows a paused notice`(half: PausedNoticeHalf) = conformance(half) {
        half.network = OFFLINE
        half.start(this)
        tick()

        half.noticeTitles() shouldBe listOf(PAUSED)
    }

    companion object {
        @JvmStatic
        fun halves() = listOf(MangaPausedNoticeHalf(), NovelPausedNoticeHalf())
    }
}

// Past both engines' rechecks: Mihon's worker polls every second, the novel drain every five.
private fun TestScope.tick() {
    advanceTimeBy(6_000L)
    runCurrent()
}

private const val NO_NETWORK = "no network"
private const val NO_WIFI = "no wifi"
private const val PAUSED = "paused"

/** Over the shade's stand-in for every string, so the two reasons and the paused title read as themselves. */
private fun stubNoNetworkText() {
    every { any<Context>().stringResource(MR.strings.download_notifier_no_network) } returns NO_NETWORK
    every { any<Context>().stringResource(MR.strings.download_notifier_text_only_wifi) } returns NO_WIFI
    every { any<Context>().stringResource(MR.strings.chapter_paused) } returns PAUSED
}

interface PausedNoticeHalf : AutoCloseable {
    /** The network the engine sees, from the next start or recheck on. */
    var network: NetworkState

    /** How many download notices the shade shows. */
    val notices: Int

    /** What each download notice in the shade says. */
    fun noticeTexts(): List<CharSequence?>

    /** The title of each download notice in the shade. */
    fun noticeTitles(): List<CharSequence?>

    /** Posts what each of the worker's foreground requests handed its service, in order, as the service does. */
    fun postForegroundNotice()

    /** Queues one chapter and runs the engine's worker, whose fetch then hangs. */
    suspend fun start(test: TestScope)

    /** The user's pause, until the worker it ends has ended. */
    fun pause(test: TestScope)

    /** The user's resume with no network, until the worker it starts is waiting for one. */
    fun resumeOffline(test: TestScope)
}

class MangaPausedNoticeHalf : PausedNoticeHalf {
    override fun toString() = "manga"

    private lateinit var shade: FakeNotificationShade
    private var fixture: DownloadWorkerFixture? = null
    private val foregroundRequests = mutableListOf<ForegroundInfo>()

    override var network = ONLINE
        set(value) {
            field = value
            fixture?.network = value
        }

    override val notices get() = shade.shown.size

    override fun noticeTexts() = shade.shown.keys.map(shade::textOf)

    override fun noticeTitles() = shade.shown.keys.map(shade::titleOf)

    override fun postForegroundNotice() = shade.postAll(foregroundRequests)

    override suspend fun start(test: TestScope) {
        shade = FakeNotificationShade()
        stubNoNetworkText()
        val f = DownloadWorkerFixture(test) { context ->
            DownloadNotifier(
                context,
                SecurityPreferences(InMemoryPreferenceStore()),
                mockk { coEvery { await(any()) } returns null },
            ) {
                mockk { coEvery { adultIdsAmong(any()) } returns emptySet() }
            }
        }
        fixture = f
        f.network = network
        coEvery { any<CoroutineWorker>().setForegroundSafely() } coAnswers {
            foregroundRequests += firstArg<CoroutineWorker>().getForegroundInfo()
        }
        f.queue()
        f.startWorker()
    }

    override fun pause(test: TestScope) {
        fixture!!.downloadManager.pauseDownloads()
        test.tick()
        // WorkManager takes a worker's foreground notice down with it when the worker ends.
        shade.shown.remove(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
    }

    override fun resumeOffline(test: TestScope) {
        val f = fixture!!
        f.network = OFFLINE
        f.downloadManager.startDownloads()
        // The worker that resume enqueued, as WorkManager runs it.
        f.startWorker()
        test.tick()
    }

    override fun close() {
        fixture?.close()
        if (::shade.isInitialized) shade.close()
    }
}

class NovelPausedNoticeHalf : PausedNoticeHalf {
    override fun toString() = "novel"

    private lateinit var shade: FakeNotificationShade
    private var worker: Job? = null
    private val foregroundRequests = mutableListOf<ForegroundInfo>()
    private var inFlight = CompletableDeferred<Nothing>()

    // Going offline fails the fetch in flight, as a dropped connection does.
    override var network = ONLINE
        set(value) {
            field = value
            if (!value.isOnline) inFlight.completeExceptionally(IOException("connection lost"))
        }

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val chapter = NovelChapter(
        id = 10L, novelId = novel.id, url = "u10", name = "Ch 10", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 10.0, sourceOrder = 10L, dateFetch = 0L, dateUpload = 0L, page = "",
    )
    private val source = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter(any()) } coAnswers {
            inFlight = CompletableDeferred()
            inFlight.await()
        }
    }
    private val app = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class)).also {
        every { it.applicationContext } returns it
        every { it.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
    }
    private val securityPreferences = SecurityPreferences(InMemoryPreferenceStore())
    private lateinit var manager: NovelDownloadManager

    override val notices get() = shade.shown.size

    override fun noticeTexts() = shade.shown.keys.map(shade::textOf)

    override fun noticeTitles() = shade.shown.keys.map(shade::titleOf)

    override fun postForegroundNotice() = shade.postAll(foregroundRequests)

    override suspend fun start(test: TestScope) {
        shade = FakeNotificationShade()
        stubNoNetworkText()
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } answers { network }
        // The launch restore runs on IO, which here is the test's own clock.
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns StandardTestDispatcher(test.testScheduler)
        mockkStatic(WORKER_EXTENSIONS)
        coEvery { any<CoroutineWorker>().setForegroundSafely() } coAnswers {
            foregroundRequests += firstArg<CoroutineWorker>().getForegroundInfo()
        }
        val graph = mockk<AppGraph>()
        every { graph.inject(any<NovelDownloadWorker>()) } answers {
            firstArg<NovelDownloadWorker>().setField("manager", manager)
            firstArg<NovelDownloadWorker>().setField("securityPreferences", securityPreferences)
        }
        @Suppress("UNCHECKED_CAST")
        every { (app as GraphProvider<AppGraph>).graph } returns graph
        // WorkManager runs the worker a start enqueues, and cancels it on a stop.
        mockkObject(NovelDownloadWorker.Companion)
        every { NovelDownloadWorker.start(any()) } answers {
            worker = test.backgroundScope.launch { NovelDownloadWorker(app, mockk(relaxed = true)).doWork() }
        }
        every { NovelDownloadWorker.stop(any()) } answers { worker?.cancel() }
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        manager = NovelDownloadManager(
            context = app,
            provider = mockk { every { availableSpace() } returns -1L },
            cache = mockk { every { downloadedChapterIds(novel, any()) } returns emptySet() },
            chapterRepo = mockk { coEvery { getById(chapter.id) } returns chapter },
            novelRepo = mockk<NovelRepository> { coEvery { getById(novel.id) } returns novel },
            sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get(novel.source) } returns source },
            installer = mockk { coEvery { ensureLoaded() } just runs },
            downloadPreferences = downloadPreferences,
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            saver = mockk(),
            securityPreferences = securityPreferences,
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            sourceTitles = mockk(),
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
        )
        manager.downloadChapters(listOf(chapter))
    }

    override fun pause(test: TestScope) {
        manager.pauseDownloads()
        test.tick()
        // WorkManager takes a worker's foreground notice down with it when the worker ends.
        shade.shown.remove(Notifications.ID_NOVEL_DOWNLOADER)
    }

    override fun resumeOffline(test: TestScope) {
        network = OFFLINE
        manager.startDownloads()
        test.tick()
    }

    override fun close() {
        unmockkObject(NovelDownloadWorker.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(Dispatchers::class)
        unmockkStatic(WORKER_EXTENSIONS)
        if (::shade.isInitialized) shade.close()
    }

    private fun NovelDownloadWorker.setField(name: String, value: Any) {
        NovelDownloadWorker::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private companion object {
        const val WORKER_EXTENSIONS = "eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt"
    }
}
