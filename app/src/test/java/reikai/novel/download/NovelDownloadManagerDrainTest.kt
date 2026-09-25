package reikai.novel.download

import android.app.NotificationManager
import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences

/**
 * A pause cancels the worker, but a chapter mid-save cannot notice until its blocking write returns,
 * so a Resume in that window starts a second drain while the first is still unwinding.
 */
class NovelDownloadManagerDrainTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val chapters = listOf(10L, 11L).map { id ->
        NovelChapter(
            id = id,
            novelId = 1L,
            url = "u$id",
            name = "Ch $id",
            read = false,
            bookmark = false,
            lastTextProgress = 0L,
            chapterNumber = id.toDouble(),
            sourceOrder = id,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )
    }

    /** Holds the first chapter's fetch the way a blocking write holds a cancelled worker. */
    private val firstFetch = CompletableDeferred<Unit>()

    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val chapterRepo = mockk<NovelChapterRepository> {
        chapters.forEach { ch -> coEvery { getById(ch.id) } returns ch }
    }
    private val source = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter("u10") } coAnswers {
            withContext(NonCancellable) { firstFetch.await() }
            "text"
        }
        coEvery { parseChapter("u11") } returns "text"
    }
    private val sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns source }

    private val manager = NovelDownloadManager(
        context = context,
        provider = mockk(),
        cache = mockk { every { isChapterDownloaded(novel, any()) } returns false },
        chapterRepo = chapterRepo,
        novelRepo = mockk<NovelRepository> { coEvery { getById(1L) } returns novel },
        sourceManager = sourceManager,
        installer = mockk { coEvery { ensureLoaded() } just runs },
        downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
        novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
        saver = mockk { coEvery { save(any(), any(), any(), any()) } returns true },
        securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
        adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
    )

    @BeforeEach
    fun setUp() {
        mockkObject(NovelDownloadJob.Companion)
        every { NovelDownloadJob.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelDownloadJob.Companion)
        unmockkStatic(Context::activeNetworkState)
    }

    @Test
    fun `a drain started while a paused one is still unwinding finishes the queue`() = runTest {
        manager.downloadChapters(chapters)
        val paused = launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        paused.cancel()

        launch { manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> }) }
        runCurrent()
        firstFetch.complete(Unit)
        advanceUntilIdle()

        manager.queueState.value shouldBe emptyList()
    }
}
