package reikai.novel.download

import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File
import kotlin.time.Instant

/**
 * A chapter written while the index scans the disk stays in the index, as Mihon's DownloadCache keeps it
 * by holding its lock across the whole scan. The write lands inside a rescan, between the novel folder's
 * listing and the swap: the listing runs what the write queued on the test's scheduler there and then, so
 * only the scan's lock can hold it back until the swap.
 */
class NovelDownloadCacheRenewTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val onDisk = chapter(10L, "old")
    private val written = chapter(11L, "new")

    private lateinit var cache: NovelDownloadCache

    /** Writes a chapter the next time a scan lists the novel folder, once armed. */
    private var duringScan: (() -> Unit)? = null

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
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        cache = NovelDownloadCache(
            scope = scope,
            context = mockk(relaxed = true) { every { cacheDir } returns this@NovelDownloadCacheRenewTest.cacheDir },
            storageManager = mockk {
                every { changes } returns MutableSharedFlow()
                every { getNovelDownloadsDirectory() } returns
                    mockk { every { listFiles() } returns arrayOf(sourceDir) }
            },
            provider = provider,
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        )
        // The startup scan runs here, before the write is armed; the write rides the rescan.
        advanceUntilIdle()
        duringScan = {
            cache.addChapter(novel, written)
            runCurrent()
        }
        cache.invalidate()
        advanceUntilIdle()
        val indexed = cache.isChapterDownloaded(novel, onDisk) to cache.isChapterDownloaded(novel, written)
        scope.cancel()

        indexed shouldBe (true to true)
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
}
