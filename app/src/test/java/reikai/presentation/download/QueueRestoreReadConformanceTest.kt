package reikai.presentation.download

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.download.SeriesCompletions
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * Both providers draw a snapshot from the live queue after reading the restore flag, never from the
 * queue the emission carried. Here the restore lands on the first read, whichever it is, and the
 * emission still carries the empty queue from before it.
 */
class QueueRestoreReadConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a restore landing mid-read never shows the old empty queue as restored`(type: Type) = runTest {
        val snapshot = type.provider().snapshots.first()

        (snapshot.chapters.size to snapshot.restored) shouldBe (1 to false)
    }

    enum class Type {
        MANGA {
            override fun provider(): DownloadQueueProvider {
                val queue = RestoringQueue(Download(mockk(relaxed = true), Manga.create(), Chapter.create()))
                return MangaDownloadQueueProvider(
                    downloadManager = mockk {
                        every { queueState } returns queue
                        every { isQueueRestored } answers { queue.readFlag() }
                        every { isDownloaderRunning } returns MutableStateFlow(false)
                        every { statusFlow() } returns emptyFlow()
                        every { progressFlow() } returns emptyFlow()
                    },
                    downloader = mockk<Downloader> { every { completions } returns SeriesCompletions() },
                    downloadPreferences = DownloadPreferences(EmittingPreferenceStore()),
                )
            }
        },
        NOVEL {
            override fun provider(): DownloadQueueProvider {
                val queue = RestoringQueue(NovelDownload(novelId = 1L, chapterId = 2L, url = "/c"))
                return NovelDownloadQueueProvider(
                    downloadManager = mockk<NovelDownloadManager> {
                        every { queueState } returns queue
                        every { isQueueRestored } answers { queue.readFlag() }
                        every { downloadingNovelIds } returns MutableStateFlow(emptySet())
                        every { completions } returns SeriesCompletions()
                    },
                    novelRepo = mockk(relaxed = true),
                    chapterRepo = mockk(),
                    sourceManager = mockk(relaxed = true),
                )
            }
        },
        ;

        abstract fun provider(): DownloadQueueProvider
    }

    /** Emits the empty queue once; the first read of the flag or the value completes the restore. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class RestoringQueue<T>(private val saved: T) : StateFlow<List<T>> {
        private var restored = false

        fun readFlag(): Boolean = restored.also { restored = true }

        override val value: List<T>
            get() = (if (restored) listOf(saved) else emptyList()).also { restored = true }

        override val replayCache: List<List<T>> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<List<T>>): Nothing {
            collector.emit(emptyList())
            awaitCancellation()
        }
    }
}
