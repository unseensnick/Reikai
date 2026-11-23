package reikai.novel.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.util.resetVirtualDispatchers
import reikai.util.stubUniFileStatics
import reikai.util.testAppScope
import reikai.util.unstubUniFileStatics
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/**
 * Removing a batch of a novel's chapters from the download index, over a real disk the index first scans.
 * The cache works on the test's virtual dispatchers.
 */
class NovelDownloadCacheRemoveTest {

    @TempDir
    lateinit var root: File

    @TempDir
    lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        stubUniFileStatics()
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() {
        resetVirtualDispatchers()
        unstubUniFileStatics()
    }

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val removed = (1L..3L).map(::chapter)
    private val kept = chapter(4L)

    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    private val storageManager by lazy {
        mockk<StorageManager> {
            every { getNovelDownloadsDirectory() } returns UniFile.fromFile(root)
            every { changes } returns MutableSharedFlow()
        }
    }
    private val provider by lazy {
        NovelDownloadProvider(
            scope = testAppScope(),
            context = mockk(),
            storageManager = storageManager,
            downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
            libraryPreferences = libraryPreferences,
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
        )
    }

    /** A cache over four downloaded chapters, returned once its first scan has indexed them. */
    private suspend fun TestScope.scannedCache(): NovelDownloadCache {
        (removed + kept).forEach { provider.writeChapter(novel, it, "<p>${it.name}</p>") }
        val cache = NovelDownloadCache(
            scope = testAppScope(),
            context = mockk(relaxed = true) { every { cacheDir } returns this@NovelDownloadCacheRemoveTest.cacheDir },
            storageManager = storageManager,
            provider = provider,
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        )
        advanceUntilIdle()
        cache.getDownloadCount(novel) shouldBe 4
        return cache
    }

    @Test
    fun `removed chapters leave the index`() = runTest {
        val cache = scannedCache()

        cache.removeChapters(novel, removed)
        advanceUntilIdle()

        removed.none { cache.isChapterDownloaded(novel, it) } shouldBe true
    }

    @Test
    fun `a chapter left out of the batch stays in the index`() = runTest {
        val cache = scannedCache()

        cache.removeChapters(novel, removed)
        advanceUntilIdle()

        cache.isChapterDownloaded(novel, kept) shouldBe true
    }

    /** Every change rebuilds the novel's page and the library, so a batch is one change, not one per chapter. */
    @Test
    fun `removing a batch of one novel's chapters signals one change`() = runTest {
        val cache = scannedCache()
        val signals = AtomicInteger()
        val collector = launch { cache.changes.collect { signals.incrementAndGet() } }
        // The replayed signal and the scan's own arrive first; only what follows the batch is counted.
        advanceUntilIdle()
        val before = signals.get()

        cache.removeChapters(novel, removed)
        // Run to idle, the index's delayed save included, so a late signal is counted rather than missed.
        advanceUntilIdle()
        collector.cancel()

        signals.get() - before shouldBe 1
    }

    private fun chapter(id: Long) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "/c/$id",
        name = "Chapter $id",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null,
        page = "",
    )
}
