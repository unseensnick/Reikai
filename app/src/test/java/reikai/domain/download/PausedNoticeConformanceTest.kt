package reikai.domain.download

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadNotifier
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.OFFLINE
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
import reikai.novel.download.NovelDownloadJob
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR

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

/** Over the shade's stand-in for every string, so the no-network reason reads as itself. */
private fun stubNoNetworkText() {
    every { any<Context>().stringResource(MR.strings.download_notifier_no_network) } returns NO_NETWORK
}

interface PausedNoticeHalf : AutoCloseable {
    /** How many download notices the shade shows. */
    val notices: Int

    /** What each download notice in the shade says. */
    fun noticeTexts(): List<CharSequence?>

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

    override val notices get() = shade.shown.size

    override fun noticeTexts() = shade.shown.keys.map(shade::textOf)

    override suspend fun start(test: TestScope) {
        shade = FakeNotificationShade()
        stubNoNetworkText()
        val f = DownloadWorkerFixture(test) { context ->
            DownloadNotifier(context, SecurityPreferences(InMemoryPreferenceStore())) {
                mockk { coEvery { adultIdsAmong(any()) } returns emptySet() }
            }
        }
        fixture = f
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
    private var network = NetworkState(isConnected = true, isValidated = true, isWifi = true)
    private var worker: Job? = null

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val chapter = NovelChapter(
        id = 10L, novelId = novel.id, url = "u10", name = "Ch 10", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 10.0, sourceOrder = 10L, dateFetch = 0L, dateUpload = 0L, page = "",
    )
    private val source = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter(any()) } coAnswers { CompletableDeferred<Nothing>().await() }
    }
    private val app = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class)).also {
        every { it.applicationContext } returns it
        every { it.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
    }
    private val securityPreferences = SecurityPreferences(InMemoryPreferenceStore())
    private lateinit var manager: NovelDownloadManager

    override val notices get() = shade.shown.size

    override fun noticeTexts() = shade.shown.keys.map(shade::textOf)

    override suspend fun start(test: TestScope) {
        shade = FakeNotificationShade()
        stubNoNetworkText()
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } answers { network }
        // The launch restore runs on IO, which here is the test's own clock.
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns StandardTestDispatcher(test.testScheduler)
        mockkStatic(WORKER_EXTENSIONS)
        coEvery { any<CoroutineWorker>().setForegroundSafely() } just runs
        val graph = mockk<AppGraph>()
        every { graph.inject(any<NovelDownloadJob>()) } answers {
            firstArg<NovelDownloadJob>().setField("manager", manager)
            firstArg<NovelDownloadJob>().setField("securityPreferences", securityPreferences)
        }
        @Suppress("UNCHECKED_CAST")
        every { (app as GraphProvider<AppGraph>).graph } returns graph
        // WorkManager runs the worker a start enqueues, and cancels it on a stop.
        mockkObject(NovelDownloadJob.Companion)
        every { NovelDownloadJob.start(any()) } answers {
            worker = test.backgroundScope.launch { NovelDownloadJob(app, mockk(relaxed = true)).doWork() }
        }
        every { NovelDownloadJob.stop(any()) } answers { worker?.cancel() }
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
        network = NetworkState(isConnected = false, isValidated = false, isWifi = false)
        manager.startDownloads()
        test.tick()
    }

    override fun close() {
        unmockkObject(NovelDownloadJob.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(Dispatchers::class)
        unmockkStatic(WORKER_EXTENSIONS)
        if (::shade.isInitialized) shade.close()
    }

    private fun NovelDownloadJob.setField(name: String, value: Any) {
        NovelDownloadJob::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private companion object {
        const val WORKER_EXTENSIONS = "eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt"
    }
}
