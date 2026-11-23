package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelTapLayout
import reikai.domain.reader.ChapterProgress
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import kotlin.coroutines.ContinuationInterceptor

/** The novel session's half of the viewport lifecycle, over a real model on [NovelReaderViewModelHarness]. */
class NovelReaderProviderTest {

    /** Where IO and Default run, apart from Main, so a test can tell which one a step ran on. */
    private lateinit var background: TestDispatcher

    @BeforeEach
    fun setUp() {
        background = virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    private fun readerTest(block: suspend TestScope.(NovelReaderViewModelHarness) -> Unit) = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { block(it) }
    }

    /** The filter is applied to the whole renderer, so the colour the host paints around it must take it too. */
    @Test
    fun `the page colour the host paints takes the session's colour filter`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val context = mockk<android.content.Context>(relaxed = true)
        val plain = provider.pageBackground(context).first()

        harness.novelPreferences.readerInvertedColors().set(true)

        provider.pageBackground(context).first() shouldBe PageColorAdjust(inverted = true).applyTo(plain)
    }

    /** Speech outlives the Activity, so a renderer left attached would be asked about a destroyed page. */
    @Test
    fun `a detached viewport is no longer asked where the reader is`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val viewport = FakeTextViewport()
        model.readAloud.attach(viewport.readAloud)

        provider.detach(viewport)
        model.readAloud.readFromHere()
        advanceUntilIdle()

        viewport.asked shouldBe false
    }

    /** A tachiyomi-format extension builds the address with code of its own, as a manga source does. */
    @Test
    fun `the open chapter's web address is built off the main thread`() = readerTest { harness ->
        val source = harness.source("src")
        var builtOn: ContinuationInterceptor? = null
        source.resolve = {
            builtOn = currentCoroutineContext()[ContinuationInterceptor]
            "https://src.example/1"
        }
        val novel = harness.novel(source)
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)

        provider.webUrl.first { it != null }

        builtOn shouldBe background
    }

    @Test
    fun `a failed chapter's web address is built off the main thread`() = readerTest { harness ->
        val source = harness.source("src")
        var builtOn: ContinuationInterceptor? = null
        source.resolve = {
            builtOn = currentCoroutineContext()[ContinuationInterceptor]
            "https://src.example/1"
        }
        val novel = harness.novel(source)
        val chapter = harness.chapter(novel, 1.0)
        val model = harness.open(novel, chapter.id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)

        provider.chapterWebUrl(chapter.id)

        builtOn shouldBe background
    }

    /** The viewport is built once this opens; built from the default, it was rebuilt with the chapter. */
    @Test
    fun `the novel's own renderer is in place once the viewport may be built`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        harness.updateNovel(novel) { viewerFlags = NovelRenderingMode.flagOf(NovelRenderingMode.WEBVIEW) }
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)

        model.viewerFlagsLoaded.await()

        model.renderingMode.value shouldBe NovelRenderingMode.WEBVIEW
    }

    @Test
    fun `changing the tap layout reaches the overlay`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val emissions = mutableListOf<NovelTapZones>()
        backgroundScope.launch { provider.tapZoneChanges.collect { emissions += it } }
        runCurrent()

        harness.novelPreferences.readerTapLayout(paged = false).set(NovelTapLayout.THIRDS)
        advanceUntilIdle()
        // The collector is background work, which advanceUntilIdle leaves queued.
        runCurrent()

        emissions.last().layout shouldBe NovelTapLayout.THIRDS
    }

    /** Every reader setting arrives in one value, so the zones must not re-emit for an unrelated change. */
    @Test
    fun `a change to another reader setting does not show the overlay again`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val emissions = mutableListOf<NovelTapZones>()
        backgroundScope.launch { provider.tapZoneChanges.collect { emissions += it } }
        runCurrent()

        harness.novelPreferences.readerFontSize().set(20)
        advanceUntilIdle()
        runCurrent()

        emissions.size shouldBe 1
    }

    @Test
    fun `paged reading opens with the pager's tap layout`() = readerTest { harness ->
        harness.novelPreferences.readerPageLayout().set(NovelPageLayout.PAGED_LTR)
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val emissions = mutableListOf<NovelTapZones>()
        backgroundScope.launch { provider.tapZoneChanges.collect { emissions += it } }
        runCurrent()

        emissions.last().layout shouldBe NovelTapLayout.RIGHT_AND_LEFT
    }

    /** As a manga with its own reading mode names it on open. */
    @Test
    fun `a novel opens naming its own reading mode`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        harness.updateNovel(novel) { viewerFlags = NovelPageLayout.flagOf(NovelPageLayout.PAGED_RTL) }
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)

        provider.readingModeNotice() shouldBe NovelPageLayout.PAGED_RTL.titleRes
    }

    /** Manga names its mode when the viewer changes, not when a setting beside it does. */
    @Test
    fun `a rebuild for a setting other than the reading mode names nothing`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        harness.updateNovel(novel) { viewerFlags = NovelPageLayout.flagOf(NovelPageLayout.PAGED_RTL) }
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)

        provider.readingModeNotice(rebuiltFrom = NovelPageLayout.PAGED_RTL) shouldBe null
    }

    @Test
    fun `a rebuild into another reading mode names it`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        harness.updateNovel(novel) { viewerFlags = NovelPageLayout.flagOf(NovelPageLayout.PAGED_RTL) }
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)

        provider.readingModeNotice(rebuiltFrom = NovelPageLayout.PAGED_LTR) shouldBe NovelPageLayout.PAGED_RTL.titleRes
    }

    @Test
    fun `a novel opens without naming its reading mode when Show reading mode is off`() = readerTest { harness ->
        harness.novelPreferences.readerShowReadingMode().set(false)
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)

        provider.readingModeNotice() shouldBe null
    }

    /** Kept paged while every other novel scrolls, as the For this series row sets it. */
    @Test
    fun `a novel kept paged opens with the pager's tap layout over a scrolling default`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        harness.updateNovel(novel) { viewerFlags = NovelPageLayout.flagOf(NovelPageLayout.PAGED_LTR) }
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val emissions = mutableListOf<NovelTapZones>()
        backgroundScope.launch { provider.tapZoneChanges.collect { emissions += it } }
        runCurrent()

        emissions.last().layout shouldBe NovelTapLayout.RIGHT_AND_LEFT
    }

    /** The rail is a scrolling choice, so a novel kept paged gets the page slider over a scrolling default. */
    @Test
    fun `a novel kept paged gets the page slider over a scrolling default`() = readerTest { harness ->
        harness.novelPreferences.readerUseRail().set(true)
        harness.novelPreferences.readerShowNavigator().set(true)
        val novel = harness.novel(harness.source("src"))
        harness.updateNovel(novel) { viewerFlags = NovelPageLayout.flagOf(NovelPageLayout.PAGED_LTR) }
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val states = mutableListOf<ReaderNavigatorState>()
        backgroundScope.launch { provider.navigator.collect { states += it } }
        runCurrent()

        states.last().shape shouldBe ReaderNavigatorShape.Slider
    }

    @Test
    fun `scrolling keeps its own tap layout`() = readerTest { harness ->
        harness.novelPreferences.readerTapLayout(paged = true).set(NovelTapLayout.KINDLISH)
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        val provider = NovelReaderProvider(model, harness.novelPreferences, mockk(), mockk(), EnglishChapterTitleWords)
        val emissions = mutableListOf<NovelTapZones>()
        backgroundScope.launch { provider.tapZoneChanges.collect { emissions += it } }
        runCurrent()

        emissions.last().layout shouldBe NovelTapLayout.DISABLED
    }

    @Test
    fun `paged, the navigator is a page slider whatever the rail setting`() {
        val state = novelNavigatorState(
            percent = 40,
            page = ChapterProgress.Pages(lastPageRead = 3, pageCount = 9),
            layout = NovelPageLayout.PAGED_LTR,
            settings = readerSettings(useRail = true),
            show = true,
        )

        (state.shape to state.progress) shouldBe (ReaderNavigatorShape.Slider to ChapterProgress.Pages(3, 9))
    }

    @Test
    fun `scrolling, the navigator keeps the rail and the percent`() {
        val state =
            novelNavigatorState(
                percent = 40,
                page = null,
                layout = NovelPageLayout.SCROLL,
                settings = readerSettings(useRail = true),
                show = true,
            )

        (state.shape to state.progress) shouldBe (ReaderNavigatorShape.Rail to ChapterProgress.Percent.ofWhole(40))
    }

    /** A paged viewport reports no page until its first is shown, which a chapter too short to cut never is. */
    @Test
    fun `paged, the navigator is a slider before any page is reported`() {
        val state = novelNavigatorState(
            percent = 40,
            page = null,
            layout = NovelPageLayout.PAGED_LTR,
            settings = readerSettings(useRail = true),
            show = true,
        )

        state.shape shouldBe ReaderNavigatorShape.Slider
    }

    private fun readerSettings(useRail: Boolean) =
        NovelReaderSettings.read(NovelPreferences(EmittingPreferenceStore()), 0).copy(useRail = useRail)
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

        override fun highlight(position: ReadAloudPosition?, range: IntRange?, follows: Boolean) = Unit
    }

    override val view: View get() = error("no view in a unit test")

    @Composable
    override fun Content(modifier: Modifier) = Unit

    override val isRtl = false

    override fun seekTo(progress: ChapterProgress) = Unit

    override fun onChapterStepped() = Unit

    override val autoScroll = ViewportAutoScroll.Continuous(EmittingPreferenceStore().getFloat("speed", 0f)) {}

    override fun destroy() = Unit

    override fun handleKeyEvent(event: KeyEvent) = false

    override fun handleGenericMotionEvent(event: MotionEvent) = false

    override suspend fun load(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) = Unit

    override fun applySettings(settings: NovelReaderSettings) = Unit

    override val window: ChapterWindow get() = error("no window in a unit test")

    override fun setObscured(top: Int, bottom: Int) = Unit
}
