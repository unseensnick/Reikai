package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.MOBILE
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.OFFLINE
import eu.kanade.tachiyomi.data.download.DownloadWorkerFixture.Companion.ONLINE
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.IOException

/**
 * A queue without a suitable network (offline, or off Wi-Fi with Wi-Fi only on) waits for one and then
 * fetches on its own, over both real engines: Mihon's worker and downloader, and the novel drain.
 */
class NetworkWaitConformanceTest {

    private fun conformance(half: NetworkWaitHalf, body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            half.close()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue started offline fetches nothing while it waits`(half: NetworkWaitHalf) = conformance(half) {
        half.network = OFFLINE
        half.start(this, wifiOnly = false)
        tick()

        half.fetches shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue started offline fetches once the connection returns`(half: NetworkWaitHalf) = conformance(half) {
        half.network = OFFLINE
        half.start(this, wifiOnly = false)
        tick()

        half.network = ONLINE
        tick()

        half.fetches shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a queue started off Wi-Fi with Wi-Fi only on fetches once Wi-Fi returns`(half: NetworkWaitHalf) =
        conformance(half) {
            half.network = MOBILE
            half.start(this, wifiOnly = true)
            tick()

            half.network = ONLINE
            tick()

            half.fetches shouldBe 1
        }

    /** The drop is seen first and the request it broke fails a moment later, as a real one does. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a connection lost mid-download fetches the chapter again once it returns`(half: NetworkWaitHalf) =
        conformance(half) {
            half.start(this, wifiOnly = false)
            tick()
            half.network = OFFLINE
            tick()
            half.failFetchInFlight()
            tick()

            half.network = ONLINE
            tick()

            half.fetches shouldBe 2
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a chapter failing off Wi-Fi with Wi-Fi only on waits for Wi-Fi to fetch again`(half: NetworkWaitHalf) =
        conformance(half) {
            half.start(this, wifiOnly = true)
            tick()
            half.network = MOBILE
            tick()
            half.failFetchInFlight()

            tick()

            half.fetches shouldBe 1
        }

    private fun TestScope.tick() {
        advanceTimeBy(TICK_MS)
        runCurrent()
    }

    companion object {
        // Past both engines' rechecks: Mihon's worker polls every second, the novel drain every five.
        private const val TICK_MS = 6_000L

        @JvmStatic
        fun halves() = listOf(MangaNetworkWaitHalf(), NovelNetworkWaitHalf())
    }
}

interface NetworkWaitHalf : AutoCloseable {
    /** What the device's network answers, read at every check. */
    var network: NetworkState

    /** How many times the source was asked for the one queued chapter. */
    val fetches: Int

    /** Queues one chapter and starts the engine's worker, as WorkManager would. */
    suspend fun start(test: TestScope, wifiOnly: Boolean)

    /** Fails the fetch in flight; nothing when the engine already cancelled it. */
    fun failFetchInFlight()
}

class MangaNetworkWaitHalf : NetworkWaitHalf {
    override fun toString() = "manga"

    private var fixture: DownloadWorkerFixture? = null
    private var pending = ONLINE

    override var network: NetworkState
        get() = fixture?.network ?: pending
        set(value) {
            pending = value
            fixture?.network = value
        }

    override val fetches get() = fixture?.fetches ?: 0

    override suspend fun start(test: TestScope, wifiOnly: Boolean) {
        val f = DownloadWorkerFixture(test).also { fixture = it }
        f.network = pending
        f.downloadPreferences.downloadOnlyOverWifi.set(wifiOnly)
        f.queue()
        f.startWorker()
    }

    override fun failFetchInFlight() {
        fixture?.failFetchInFlight()
    }

    override fun close() {
        fixture?.close()
    }
}

class NovelNetworkWaitHalf : NetworkWaitHalf {
    override fun toString() = "novel"

    override var network = ONLINE
    override var fetches = 0
        private set

    private var inFlight = CompletableDeferred<Nothing>()
    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val chapter = NovelChapter(
        id = 10L, novelId = novel.id, url = "u10", name = "Ch 10", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 10.0, sourceOrder = 10L, dateFetch = 0L, dateUpload = 0L, page = "",
    )
    private val source = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter(any()) } coAnswers {
            fetches++
            inFlight = CompletableDeferred()
            inFlight.await()
        }
    }

    override suspend fun start(test: TestScope, wifiOnly: Boolean) {
        mockkObject(NovelDownloadWorker.Companion)
        every { NovelDownloadWorker.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } answers { network }
        // The launch restore runs on IO, which here is the test's own clock.
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns StandardTestDispatcher(test.testScheduler)
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        downloadPreferences.downloadOnlyOverWifi.set(wifiOnly)
        val manager = NovelDownloadManager(
            context = mockk<Context>(relaxed = true) {
                every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
                every { getSystemService(NotificationManager::class.java) } returns
                    mockk<NotificationManager>(relaxed = true)
            },
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
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
            sourceTitles = mockk(),
            getEntryCustomInfo = mockk { coEvery { await(any()) } returns null },
        )
        manager.downloadChapters(listOf(chapter))
        // The worker's part, which WorkManager would run.
        test.backgroundScope.launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
    }

    override fun failFetchInFlight() {
        inFlight.completeExceptionally(IOException("connection lost"))
    }

    override fun close() {
        unmockkObject(NovelDownloadWorker.Companion)
        unmockkStatic(Context::activeNetworkState)
        unmockkStatic(Dispatchers::class)
    }
}
