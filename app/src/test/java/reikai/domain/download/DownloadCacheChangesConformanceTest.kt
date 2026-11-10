package reikai.domain.download

import android.content.Context
import android.util.AtomicFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadProvider
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Each download index's change signal reaches every collector. Models collect it while their screen is
 * hidden, held back by the subscriber gate, so a single-consumer signal would let a hidden model take a
 * change the visible one needed. One rule over both indexes; the caches work on real IO threads.
 */
class DownloadCacheChangesConformanceTest {

    /** An index whose [changes] are watched, and a way to make it signal one change. */
    interface Probe {
        fun open(cacheDir: File): Pair<Flow<Unit>, suspend () -> Unit>
    }

    @TempDir
    lateinit var cacheDir: File

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `one change reaches two collectors`(probe: Probe) = runTest {
        val (changes, change) = probe.open(cacheDir)
        val first = AtomicInteger()
        val second = AtomicInteger()
        backgroundScope.launch(Dispatchers.Default) { changes.collect { first.incrementAndGet() } }
        backgroundScope.launch(Dispatchers.Default) { changes.collect { second.incrementAndGet() } }
        // The replayed signal and any start-up scan arrive first; only what follows the change counts.
        awaitQuiet(first, second)
        val firstBefore = first.get()
        val secondBefore = second.get()

        change()
        awaitQuiet(first, second)

        listOf(first.get() > firstBefore, second.get() > secondBefore) shouldBe listOf(true, true)
    }

    /** Waits until neither counter has moved for a while, so a late signal is counted rather than missed. */
    private suspend fun awaitQuiet(vararg counters: AtomicInteger) = withContext(Dispatchers.Default) {
        withTimeoutOrNull(5.seconds) {
            var last = emptyList<Int>()
            while (counters.map { it.get() } != last) {
                last = counters.map { it.get() }
                delay(QUIET_MS.milliseconds)
            }
        }
    }

    companion object {
        private const val QUIET_MS = 300L

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override fun open(cacheDir: File): Pair<Flow<Unit>, suspend () -> Unit> {
            // The index file is android.util.AtomicFile, which the JVM test jar stubs out.
            mockkConstructor(AtomicFile::class)
            every { anyConstructed<AtomicFile>().baseFile } returns File(cacheDir, "index")
            every { anyConstructed<AtomicFile>().delete() } returns Unit
            every { anyConstructed<AtomicFile>().startWrite() } throws IOException("not on the JVM")
            val cache = DownloadCache(
                scope = testAppScope(),
                context = mockk<Context>(relaxed = true) { every { this@mockk.cacheDir } returns cacheDir },
                provider = mockk(relaxed = true),
                sourceManager = mockk(relaxed = true),
                storageManager = mockk { every { changes } returns MutableSharedFlow() },
                downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            )
            val source = mockk<Source> { every { id } returns 1L }
            return cache.changes to { cache.removeSource(source) }
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override fun open(cacheDir: File): Pair<Flow<Unit>, suspend () -> Unit> {
            val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
            val storageManager = mockk<StorageManager> {
                every { getNovelDownloadsDirectory() } returns null
                every { changes } returns MutableSharedFlow()
            }
            val cache = NovelDownloadCache(
                scope = testAppScope(),
                context = mockk<Context>(relaxed = true) { every { this@mockk.cacheDir } returns cacheDir },
                storageManager = storageManager,
                provider = NovelDownloadProvider(
                    scope = testAppScope(),
                    storageManager = storageManager,
                    downloadProvider = DownloadProvider(mockk(), mockk(), libraryPreferences),
                    libraryPreferences = libraryPreferences,
                    novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
                ),
                downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            )
            val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
            return cache.changes to { cache.removeNovel(novel) }
        }
    }
}
