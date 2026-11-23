package reikai.presentation.novel.details

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownload
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import java.util.concurrent.ConcurrentHashMap

/**
 * A chapter row's download mark on the novel details list follows the live queue, as manga's does
 * (MangaViewModelSubscriptionTest). The queue only emits when it changes, so a list that loads after the
 * queue last spoke has to read what it already holds.
 */
class NovelDetailsDownloadStateTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a chapter that failed before the list loaded shows its error`() = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novelId = harness.novel(harness.source("alpha"))
            val failed = harness.chapter(novelId, 1.0)
            every { harness.downloadManager.queueState } returns MutableStateFlow(
                listOf(NovelDownload(novelId, failed.id, url = "c1", state = NovelDownload.State.ERROR)),
            )

            val model = harness.openDetails(novelId)
            val loaded = withTimeout(10_000) {
                model.state.filterIsInstance<NovelDetailsState.Loaded>().first { it.chapters.isNotEmpty() }
            }

            loaded.downloadStateOf(failed.id) shouldBe Download.State.ERROR
        }
    }

    /** M's plugin is gone and A is installed; both hold the opening, which the stitch shows from M's copy. */
    @Test
    fun `an export's missing chapters download from an installed source, or not at all`() =
        runTest {
            NovelReaderViewModelHarness.create(testScheduler).use { harness ->
                val m = harness.novel(FakeNovelSource("gone", "unregistered"))
                val a = harness.novel(harness.source("alpha"))
                harness.chapter(m, 1.0, name = "Opening")
                harness.chapter(m, 2.0, name = "Only gone has this")
                val installedOpening = harness.chapter(a, 1.0, name = "Opening")
                harness.merge(m, a)
                val queued = ConcurrentHashMap.newKeySet<Long>()
                coEvery { harness.downloadManager.downloadChapters(any()) } answers {
                    firstArg<List<NovelChapter>>().forEach { queued += it.id }
                }
                val model = harness.openDetails(m)
                withTimeout(30_000) {
                    model.state.filterIsInstance<NovelDetailsState.Loaded>().first { it.chapters.size == 2 }
                }

                model.showExportDialog()
                val missing = withTimeout(10_000) {
                    model.state.filterIsInstance<NovelDetailsState.Loaded>()
                        .mapNotNull { it.dialog as? NovelDetailsDialog.ExportEpub }
                        .first { it.onDevice != null }
                        .missing
                }
                model.downloadMissingForExport(missing)
                withTimeout(10_000) { while (queued.isEmpty()) delay(20) }
                // Anything else a wrong rule would queue lands in the same pass.
                delay(200)

                queued shouldBe setOf(installedOpening.id)
            }
        }
}
