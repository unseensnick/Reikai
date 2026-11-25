package reikai.presentation.novel.details

import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.export.EpubRun
import reikai.novel.export.NovelEpubExportWorker
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/** Export as EPUB answered by the system picker after the process died, on a model that never counted. */
class NovelExportRestoreTest {

    private val runs = mutableListOf<EpubRun?>()

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        mockkObject(NovelEpubExportWorker.Companion)
        every { NovelEpubExportWorker.start(any(), any(), any(), any(), any(), any()) } answers {
            runs += arg<EpubRun?>(5)
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelEpubExportWorker.Companion)
        resetVirtualDispatchers()
    }

    @Test
    fun `a range picked before the process died exports the chapters it named`() = runTest {
        val expected = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novelId = harness.novel(harness.source("alpha"))
            val chapters = (1..3).map { harness.chapter(novelId, it.toDouble()) }
            chapters.forEach { harness.download(it, "text") }
            val model = harness.openDetails(novelId)
            withTimeout(10_000) {
                model.state.filterIsInstance<NovelDetailsState.Loaded>().first { it.chapters.size == 3 }
            }
            harness.settleAppWrites { model.exportEpub(mockk<Uri>(), includePictures = false, from = 2, to = 3) }
            EpubRun(2, chapters[1].id, chapters[2].id)
        }

        runs shouldBe listOf(expected)
    }
}
