package reikai.domain.download

import android.content.Context
import android.util.AtomicFile
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadProvider
import reikai.storage.IN_FLIGHT_SUFFIX
import reikai.util.stubUniFileStatics
import reikai.util.testAppScope
import reikai.util.unstubUniFileStatics
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A letter-case rename of a source's or a series' download folder goes through an in-flight name, and a kill between
 * its two steps leaves the folder there, where neither index nor lookup reads it. Both types' index scans and folder
 * lookups put it back under its own name; a chapter's in-flight download inside a series' folder is left alone.
 */
class InFlightRenameRecoveryConformanceTest {

    @TempDir
    lateinit var root: File

    @TempDir
    lateinit var cacheDir: File

    @BeforeEach
    fun stubStatics() = stubUniFileStatics()

    @AfterEach
    fun tearDown() {
        unstubUniFileStatics()
        unmockkAll()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the index scan reads a series folder a killed rename left in flight`(half: RecoveryHalf) = runTest {
        half.chapter(root, "src", "Series$IN_FLIGHT_SUFFIX")

        half.awaitIndexed(root, cacheDir) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the index scan reads a source folder a killed rename left in flight`(half: RecoveryHalf) = runTest {
        half.chapter(root, "src$IN_FLIGHT_SUFFIX", "Series")

        half.awaitIndexed(root, cacheDir) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the folder lookup finds a series folder a killed rename left in flight`(half: RecoveryHalf) = runTest {
        half.chapter(root, "src", "Series$IN_FLIGHT_SUFFIX")

        half.findSeriesDir(root)?.name shouldBe "Series"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the folder lookup finds a source folder a killed rename left in flight`(half: RecoveryHalf) = runTest {
        half.chapter(root, "src$IN_FLIGHT_SUFFIX", "Series")

        half.findSeriesDir(root)?.name shouldBe "Series"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a folder in flight whose own name is taken stays in flight`(half: RecoveryHalf) = runTest {
        half.chapter(root, "src", "Series")
        File(root, "src/series$IN_FLIGHT_SUFFIX").mkdirs()

        half.awaitIndexed(root, cacheDir)

        File(root, "src").list()!!.toSet() shouldBe setOf("Series", "series$IN_FLIGHT_SUFFIX")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a chapter's in-flight download inside a series folder is left to the downloader`(half: RecoveryHalf) =
        runTest {
            half.chapter(root, "src", "Series")
            val unfinished = half.inFlightChapter(File(root, "src/Series"))

            half.awaitIndexed(root, cacheDir)
            half.findSeriesDir(root)

            unfinished.exists() shouldBe true
        }

    companion object {
        @JvmStatic
        fun halves() = listOf(MangaRecoveryHalf(), NovelRecoveryHalf())
    }
}

interface RecoveryHalf {
    /** One downloaded chapter of the series titled "Series" on source "src", written at `<source>/<series>`. */
    fun chapter(root: File, sourceFolder: String, seriesFolder: String)

    /** A chapter download not finished yet, as this type's downloader leaves one in [seriesDir]. */
    fun inFlightChapter(seriesDir: File): File

    /** Whether the index, once its first scan lands, counts the series' one chapter. */
    suspend fun awaitIndexed(root: File, cacheDir: File): Boolean

    /** The series' download folder as the provider's lookup finds it. */
    suspend fun findSeriesDir(root: File): UniFile?
}

private suspend fun awaitOnRealThreads(condition: () -> Boolean): Boolean = withContext(Dispatchers.Default) {
    withTimeoutOrNull(20.seconds) {
        while (!condition()) delay(10.milliseconds)
        true
    } ?: false
}

class MangaRecoveryHalf : RecoveryHalf {
    override fun toString() = "manga"

    private val manga = Manga.create().copy(id = 1L, source = 1L, title = "Series")
    private val source = mockk<HttpSource> {
        every { id } returns 1L
        every { this@mockk.toString() } returns "src"
    }

    private fun provider(root: File) = DownloadProvider(
        mockk(relaxed = true),
        mockk { every { getDownloadsDirectory() } returns UniFile.fromFile(root) },
        LibraryPreferences(InMemoryPreferenceStore()),
    )

    override fun chapter(root: File, sourceFolder: String, seriesFolder: String) {
        File(root, "$sourceFolder/$seriesFolder/Chapter 1").mkdirs()
    }

    override fun inFlightChapter(seriesDir: File): File =
        File(seriesDir, "Chapter 2$IN_FLIGHT_SUFFIX").also { it.mkdirs() }

    override suspend fun awaitIndexed(root: File, cacheDir: File): Boolean {
        // The index file is android.util.AtomicFile, which the JVM test jar stubs out.
        mockkConstructor(AtomicFile::class)
        every { anyConstructed<AtomicFile>().baseFile } returns File(cacheDir, "index")
        every { anyConstructed<AtomicFile>().delete() } returns Unit
        every { anyConstructed<AtomicFile>().startWrite() } throws IOException("not on the JVM")
        // Cancelled once read: the index's delayed write reads the folders' uris, which outlive the stubs.
        val scope = testAppScope()
        val cache = DownloadCache(
            scope = scope,
            context = mockk<Context>(relaxed = true) { every { this@mockk.cacheDir } returns cacheDir },
            provider = provider(root),
            sourceManager = mockk {
                coEvery { getOnlineSources() } returns listOf(source)
                coEvery { getStubSources() } returns emptyList()
            },
            storageManager = mockk {
                every { changes } returns MutableSharedFlow()
                every { getDownloadsDirectory() } returns UniFile.fromFile(root)
            },
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        )
        return awaitOnRealThreads { cache.getDownloadCount(manga) == 1 }.also { scope.cancel() }
    }

    override suspend fun findSeriesDir(root: File): UniFile? = provider(root).findMangaDir(manga.title, source)
}

class NovelRecoveryHalf : RecoveryHalf {
    override fun toString() = "novel"

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Series")

    override fun chapter(root: File, sourceFolder: String, seriesFolder: String) {
        File(root, "$sourceFolder/$seriesFolder").mkdirs()
        File(root, "$sourceFolder/$seriesFolder/Chapter 1.html").writeText("<p>1</p>")
    }

    override fun inFlightChapter(seriesDir: File): File =
        File(seriesDir, "Chapter 2.html$IN_FLIGHT_SUFFIX").also { it.writeText("<p>2</p>") }

    private fun provider(root: File): NovelDownloadProvider {
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        return NovelDownloadProvider(
            scope = testAppScope(),
            context = mockk(),
            storageManager = storageManager(root),
            downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
            libraryPreferences = libraryPreferences,
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
        )
    }

    private fun storageManager(root: File) = mockk<StorageManager> {
        every { getNovelDownloadsDirectory() } returns UniFile.fromFile(root)
        every { changes } returns MutableSharedFlow()
    }

    override suspend fun awaitIndexed(root: File, cacheDir: File): Boolean {
        val cache = NovelDownloadCache(
            scope = testAppScope(),
            context = mockk(relaxed = true) { every { this@mockk.cacheDir } returns cacheDir },
            storageManager = storageManager(root),
            provider = provider(root),
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        )
        return awaitOnRealThreads { cache.getDownloadCount(novel) == 1 }
    }

    override suspend fun findSeriesDir(root: File): UniFile? = provider(root).findNovelDir(novel)
}
