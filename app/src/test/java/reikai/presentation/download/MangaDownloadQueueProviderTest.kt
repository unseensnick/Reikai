package reikai.presentation.download

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.download.SeriesCompletions
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga

class MangaDownloadQueueProviderTest {

    private val manga = Manga.create().copy(id = 1L)

    private suspend fun activeSeries(paused: Boolean): Set<Long> {
        val provider = MangaDownloadQueueProvider(
            downloadManager = mockk<DownloadManager> {
                every { queueState } returns
                    MutableStateFlow(listOf(Download(mockk(relaxed = true), manga, Chapter.create())))
                every { isQueueRestored } returns true
                every { isDownloaderRunning } returns MutableStateFlow(true)
                every { statusFlow() } returns emptyFlow()
                every { progressFlow() } returns emptyFlow()
            },
            downloader = mockk<Downloader> {
                every { completions } returns SeriesCompletions()
                every { pausedChanges } returns MutableStateFlow(paused)
            },
            downloadPreferences = DownloadPreferences(EmittingPreferenceStore()),
        )
        return provider.snapshots.first().activeSeries
    }

    @Test
    fun `a running downloader shows its series as downloading`() = runTest {
        activeSeries(paused = false) shouldBe setOf(1L)
    }

    /** The novel drain's lanes hold nothing through the same wait, so both read Queued. */
    @Test
    fun `a downloader paused for the network shows nothing as downloading`() = runTest {
        activeSeries(paused = true) shouldBe emptySet()
    }

    @Test
    fun `a drag that keeps a split queue's series order leaves the downloader alone`() = runTest {
        val other = Manga.create().copy(id = 2L)
        val downloadManager = mockk<DownloadManager>(relaxed = true) {
            every { queueState } returns MutableStateFlow(
                listOf(manga, other, manga).map { Download(mockk(relaxed = true), it, Chapter.create()) },
            )
        }
        val provider = MangaDownloadQueueProvider(downloadManager, mockk(relaxed = true), mockk(relaxed = true))

        provider.reorderSeries(listOf(1L, 2L))

        coVerify(exactly = 0) { downloadManager.reorderQueue(any()) }
    }

    private fun queueOfChapters(vararg numbers: Double): DownloadManager {
        val downloads = numbers.map { number ->
            Download(mockk(relaxed = true), manga, Chapter.create().copy(id = number.toLong(), chapterNumber = number))
        }
        return mockk<DownloadManager>(relaxed = true) { every { queueState } returns MutableStateFlow(downloads) }
    }

    @Test
    fun `a sort the queue already holds leaves the downloader alone`() = runTest {
        val downloadManager = queueOfChapters(1.0, 2.0)
        val provider = MangaDownloadQueueProvider(downloadManager, mockk(relaxed = true), mockk(relaxed = true))

        provider.sort(DownloadQueueSortKey.CHAPTER_NUMBER, descending = false)

        coVerify(exactly = 0) { downloadManager.reorderQueue(any()) }
    }

    @Test
    fun `a sort that changes the chapter order reorders the downloader`() = runTest {
        val downloadManager = queueOfChapters(2.0, 1.0)
        val provider = MangaDownloadQueueProvider(downloadManager, mockk(relaxed = true), mockk(relaxed = true))

        provider.sort(DownloadQueueSortKey.CHAPTER_NUMBER, descending = false)

        coVerify { downloadManager.reorderQueue(match { list -> list.map { it.chapter.id } == listOf(1L, 2L) }) }
    }
}
