package reikai.novel.download

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.util.testAppScope
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A chapter written while the index scans the disk stays in the index, as Mihon's DownloadCache keeps it
 * by holding its lock across the whole scan. The write lands inside the scan, between the novel folder's
 * listing and the swap, on the cache's own IO threads, so the checks wait in real time.
 */
class NovelDownloadCacheRenewTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val onDisk = chapter(10L, "old")
    private val written = chapter(11L, "new")

    private lateinit var cache: NovelDownloadCache

    /** Writes a chapter the first time a scan lists the novel folder, and gives the write time to land. */
    private var duringScan: (() -> Unit)? = {
        // The constructor starts the scan, so on a busy runner it can get here before [cache] is assigned.
        val ready = System.currentTimeMillis() + WRITE_WINDOW_MS
        while (!::cache.isInitialized && System.currentTimeMillis() < ready) Thread.sleep(1)
        cache.addChapter(novel, written)
        val until = System.currentTimeMillis() + WRITE_WINDOW_MS
        while (!cache.isChapterDownloaded(novel, written) && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    private val novelDir = mockk<UniFile> {
        every { isDirectory } returns true
        every { name } returns "Novel"
        every { listFiles() } answers {
            duringScan?.also { duringScan = null }?.invoke()
            arrayOf(mockk<UniFile> { every { name } returns "old.html" })
        }
    }
    private val sourceDir = mockk<UniFile> {
        every { isDirectory } returns true
        every { name } returns "src"
        every { listFiles() } returns arrayOf(novelDir)
    }

    private val provider = mockk<NovelDownloadProvider> {
        every { sourceFolders } returns MutableStateFlow(emptyMap())
        every { sourceDirName(any<Novel>()) } returns "src"
        every { novelDirName(any<Novel>()) } returns "Novel"
        every { sourceDirName(any<String>()) } returns "src"
        every { novelDirName(any<String>()) } returns "Novel"
        every { chapterFileName(any<NovelChapter>()) } answers { "${firstArg<NovelChapter>().name}.html" }
        every { validChapterFileNames(any<String>(), any()) } answers { listOf("${firstArg<String>()}.html") }
    }

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `a chapter written while the index scans stays downloaded`() = runTest {
        cache = NovelDownloadCache(
            scope = testAppScope(),
            context = mockk(relaxed = true) { every { cacheDir } returns this@NovelDownloadCacheRenewTest.cacheDir },
            storageManager = mockk {
                every { changes } returns MutableSharedFlow()
                every { getNovelDownloadsDirectory() } returns
                    mockk { every { listFiles() } returns arrayOf(sourceDir) }
            },
            provider = provider,
        )
        // The scanned chapter shows once the scan has swapped its result in; only then is the write judged.
        awaitOnRealThreads { cache.isChapterDownloaded(novel, onDisk) } shouldBe true

        awaitOnRealThreads { cache.isChapterDownloaded(novel, written) } shouldBe true
    }

    private suspend fun awaitOnRealThreads(condition: () -> Boolean): Boolean = withContext(Dispatchers.Default) {
        withTimeoutOrNull(AWAIT_LIMIT) {
            while (!condition()) delay(10.milliseconds)
            true
        } ?: false
    }

    private fun chapter(id: Long, name: String) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = name,
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null,
        page = "",
    )

    private companion object {
        // How long the scan waits for the write to land before it swaps; the fixed cache makes it wait out.
        const val WRITE_WINDOW_MS = 1_000L

        // A ceiling, not a wait: each check returns as soon as it holds. A busy CI runner can take seconds
        // just to start the scan.
        val AWAIT_LIMIT = 20.seconds
    }
}
