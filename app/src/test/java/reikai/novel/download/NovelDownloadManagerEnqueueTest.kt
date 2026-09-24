package reikai.novel.download

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Enqueueing a novel chapter already on disk queues nothing, whichever caller asked, as Mihon's
 * Downloader.queueChapters drops a chapter whose folder exists. Download selected on a mixed selection
 * used to fetch the downloaded chapters again.
 */
class NovelDownloadManagerEnqueueTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val onDisk = chapter(10L)
    private val missing = chapter(11L)

    private val cache = mockk<NovelDownloadCache> {
        every { isChapterDownloaded(novel, onDisk) } returns true
        every { isChapterDownloaded(novel, missing) } returns false
    }
    private val novelRepository = mockk<NovelRepository> { coEvery { getById(1L) } returns novel }
    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true) {
            every { all } returns emptyMap<String, Any>()
        }
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }

    private val manager = NovelDownloadManager(
        context = context,
        provider = mockk(),
        cache = cache,
        chapterRepo = mockk(),
        novelRepo = novelRepository,
        sourceManager = mockk(),
        installer = mockk(),
        downloadPreferences = mockk(),
        sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
        novelPreferences = mockk(),
        saver = mockk(),
        securityPreferences = mockk(),
        adultChecker = mockk(),
    )

    @BeforeEach
    fun setUp() {
        // Starting the drain needs WorkManager, which is not under test.
        mockkObject(NovelDownloadJob.Companion)
        every { NovelDownloadJob.start(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelDownloadJob.Companion)
    }

    @Test
    fun `a chapter already on disk is not queued`() = runTest {
        manager.downloadChapters(listOf(onDisk, missing))

        manager.queueState.value.map { it.chapterId } shouldBe listOf(11L)
    }

    @Test
    fun `a selection wholly on disk queues nothing`() = runTest {
        manager.downloadChapters(listOf(onDisk))

        manager.queueState.value shouldBe emptyList()
    }

    private fun chapter(id: Long) = NovelChapter(
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
