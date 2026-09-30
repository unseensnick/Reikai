package reikai.presentation.novel.details

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.download.NovelDownload
import reikai.presentation.reader.NovelReaderViewModelHarness

/**
 * A chapter row's download mark on the novel details list follows the live queue, as manga's does
 * (MangaDetailsDownloadRowsTest). The queue only emits when it changes, so a list that loads after the
 * queue last spoke has to read what it already holds.
 */
class NovelDetailsDownloadStateTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a chapter that failed before the list loaded shows its error`() = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novelId = harness.novel(harness.source("alpha"))
            val failed = harness.chapter(novelId, 1.0)
            every { harness.downloadManager.queueState } returns MutableStateFlow(
                listOf(NovelDownload(novelId, failed.id, url = "c1", state = NovelDownload.State.ERROR)),
            )

            val model = harness.openDetails(novelId)
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    model.state.filterIsInstance<NovelDetailsState.Loaded>().first { it.chapters.isNotEmpty() }
                }
            }

            loaded.downloadStateOf(failed.id) shouldBe Download.State.ERROR
        }
    }
}
