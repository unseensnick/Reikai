package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.local.LocalNovelSource

/** A local novel's chapters are its own files, so the seam marks them on the device, as manga's transition does. */
class NovelReaderLocalSeamTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a local novel's chapter reads as downloaded with nothing downloaded`() = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novel = harness.novel(harness.source(LocalNovelSource.ID))
            val first = harness.chapter(novel, 1.0)
            val model = harness.open(novel, first.id)
            advanceUntilIdle()

            model.window.value.chapters.single { it.chapterId == first.id }.downloaded shouldBe true
        }
    }
}
