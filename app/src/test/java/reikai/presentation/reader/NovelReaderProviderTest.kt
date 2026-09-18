package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress

/** The novel session's half of the viewport lifecycle, over a real model on [NovelReaderViewModelHarness]. */
class NovelReaderProviderTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun readerTest(block: suspend TestScope.(NovelReaderViewModelHarness) -> Unit) = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { block(it) }
    }

    /** Speech outlives the Activity, so a renderer left attached would be asked about a destroyed page. */
    @Test
    fun `a detached viewport is no longer asked where the reader is`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), EnglishChapterTitleWords)
        val viewport = FakeTextViewport()
        model.readAloud.attach(viewport.readAloud)

        provider.detach(viewport)
        model.readAloud.readFromHere()
        advanceUntilIdle()

        viewport.asked shouldBe false
    }
}

private class FakeTextViewport : ReaderViewport, TextViewport {
    var asked = false
        private set

    override val readAloud = object : ReadAloudSurface {
        override suspend fun paragraphs(chapterId: Long): List<String>? = null

        override suspend fun firstVisibleParagraph(): ReadAloudPosition? {
            asked = true
            return null
        }

        override fun highlight(position: ReadAloudPosition?, range: IntRange?) = Unit
    }

    override val view: View get() = error("no view in a unit test")

    override val isRtl = false

    override fun seekTo(progress: ChapterProgress) = Unit

    override fun onChapterStepped() = Unit

    override fun destroy() = Unit

    override fun handleKeyEvent(event: KeyEvent) = false

    override fun handleGenericMotionEvent(event: MotionEvent) = false

    override suspend fun load(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) = Unit

    override fun applySettings(settings: NovelReaderSettings) = Unit

    override fun setAutoScroll(running: Boolean, pixelsPerFrame: Float) = Unit

    override val window: ChapterWindow get() = error("no window in a unit test")

    override fun setObscured(top: Int, bottom: Int) = Unit
}
