package reikai.novel.download

import android.text.TextUtils
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Removing a batch of a novel's chapters from the download index, over a real disk the index first scans.
 * The cache works on its own IO threads, so the checks wait in real time.
 */
class NovelDownloadCacheRemoveTest {

    @TempDir
    lateinit var root: File

    @TempDir
    lateinit var cacheDir: File

    // UniFile's file-backed implementation asks android.text.TextUtils, which the JVM test jar stubs out.
    @BeforeEach
    fun stubTextUtils() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    }

    @AfterEach
    fun restoreTextUtils() {
        unmockkStatic(TextUtils::class)
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
            storageManager = storageManager,
            downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
            libraryPreferences = libraryPreferences,
        )
    }

    /** A cache over four downloaded chapters, returned once its first scan has indexed them. */
    private suspend fun scannedCache(): NovelDownloadCache {
        (removed + kept).forEach { provider.writeChapter(novel, it, "<p>${it.name}</p>") }
        val cache = NovelDownloadCache(
            context = mockk(relaxed = true) { every { cacheDir } returns this@NovelDownloadCacheRemoveTest.cacheDir },
            storageManager = storageManager,
            provider = provider,
        )
        awaitOnRealThreads { cache.getDownloadCount(novel) == 4 } shouldBe true
        return cache
    }

    @Test
    fun `removed chapters leave the index`() = runTest {
        val cache = scannedCache()

        cache.removeChapters(novel, removed)

        awaitOnRealThreads { removed.none { cache.isChapterDownloaded(novel, it) } } shouldBe true
    }

    @Test
    fun `a chapter left out of the batch stays in the index`() = runTest {
        val cache = scannedCache()

        cache.removeChapters(novel, removed)
        awaitOnRealThreads { cache.getDownloadCount(novel) == 1 }

        cache.isChapterDownloaded(novel, kept) shouldBe true
    }

    /** Every change rebuilds the novel's page and the library, so a batch is one change, not one per chapter. */
    @Test
    fun `removing a batch of one novel's chapters signals one change`() = runTest {
        val cache = scannedCache()
        val signals = AtomicInteger()
        backgroundScope.launch(Dispatchers.Default) { cache.changes.collect { signals.incrementAndGet() } }
        // The replayed signal and the scan's own arrive first; only what follows the batch is counted.
        awaitQuiet(signals)
        val before = signals.get()

        cache.removeChapters(novel, removed)
        awaitOnRealThreads { cache.getDownloadCount(novel) == 1 }
        awaitQuiet(signals)

        signals.get() - before shouldBe 1
    }

    private suspend fun awaitOnRealThreads(condition: () -> Boolean): Boolean = withContext(Dispatchers.Default) {
        withTimeoutOrNull(5.seconds) {
            while (!condition()) delay(10.milliseconds)
            true
        } ?: false
    }

    /** Waits until no signal has arrived for a while, so a late one is counted rather than missed. */
    private suspend fun awaitQuiet(signals: AtomicInteger) = withContext(Dispatchers.Default) {
        var last = -1
        while (signals.get() != last) {
            last = signals.get()
            delay(QUIET_MS.milliseconds)
        }
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
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private companion object {
        const val QUIET_MS = 300L
    }
}
