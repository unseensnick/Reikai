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
import kotlinx.coroutines.test.runTest
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

/** A nearly full device refuses a novel chapter before fetching it, as Mihon's Downloader refuses a manga one. */
class NovelDownloadManagerStorageTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val chapter = NovelChapter(
        id = 10L,
        novelId = 1L,
        url = "u10",
        name = "Ch 10",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = 10.0,
        sourceOrder = 10L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val source = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter(any()) } returns "text"
    }
    private val sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns source }

    private val manager = NovelDownloadManager(
        context = context,
        provider = mockk { every { availableSpace() } returns 1024L },
        cache = mockk { every { isChapterDownloaded(novel, chapter) } returns false },
        chapterRepo = mockk<NovelChapterRepository> { coEvery { getById(10L) } returns chapter },
        novelRepo = mockk<NovelRepository> { coEvery { getById(1L) } returns novel },
        sourceManager = sourceManager,
        installer = mockk { coEvery { ensureLoaded() } just runs },
        downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
        novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
        saver = mockk { coEvery { save(any(), any(), any(), any()) } returns true },
        securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
        adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
        getNovelCategories = mockk(),
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
    fun `a chapter is refused when the device is nearly full`() = runTest {
        manager.downloadChapters(listOf(chapter))

        manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> })

        manager.queueState.value.map { it.state } shouldBe listOf(NovelDownload.State.ERROR)
    }
}
