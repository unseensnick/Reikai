package reikai.presentation.reader

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterProgress

class ReaderEngineTest {

    // Shared with runTest below, so a test can advance work the engine posted to the main thread.
    private val scheduler = TestCoroutineScheduler()

    // The engine shares the provider's flows in its own scope, which is the main one.
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun engine(provider: FakeReaderProvider = FakeReaderProvider()) = ReaderEngine(provider)

    @Test
    fun `nothing is raised to begin with`() {
        engine().dialog.value shouldBe null
    }

    @Test
    fun `raising a dialog shows it`() {
        val engine = engine()

        engine.openDialog(ReaderDialog.Settings)

        engine.dialog.value shouldBe ReaderDialog.Settings
    }

    /**
     * The single slot is the mechanism: the host raises Loading while a chapter loads, and it has to
     * take over from whatever the reader had open rather than stacking on top of it.
     */
    @Test
    fun `raising a second dialog replaces the first rather than stacking`() {
        val engine = engine()
        engine.openDialog(ReaderDialog.ChapterList)

        engine.openDialog(ReaderDialog.Loading)

        engine.dialog.value shouldBe ReaderDialog.Loading
    }

    @Test
    fun `dismissing clears the raised dialog`() {
        val engine = engine()
        engine.openDialog(ReaderDialog.ChapterList)

        engine.dismissDialog()

        engine.dialog.value shouldBe null
    }

    @Test
    fun `dismissing with nothing raised stays empty`() {
        val engine = engine()

        engine.dismissDialog()

        engine.dialog.value shouldBe null
    }

    /**
     * The load surface is the engine's, not the host's. The host raised it from a collector rebuilt
     * on every Activity recreation, and a failure stays Failed until the next load, so a rotation
     * reopened a failure the reader had cancelled.
     */
    @Test
    fun `a failed load raises the failure it reports`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        provider.loadState.value = ReaderLoadState.Failed("no connection", canKeepReading = true)

        engine.dialog.value shouldBe ReaderDialog.LoadFailed("no connection", canKeepReading = true)
    }

    @Test
    fun `a load in flight raises the loading dialog`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        provider.loadState.value = ReaderLoadState.Loading

        engine.dialog.value shouldBe ReaderDialog.Loading
    }

    @Test
    fun `the chapter arriving clears the loading dialog`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)
        provider.loadState.value = ReaderLoadState.Loading

        provider.loadState.value = ReaderLoadState.Idle

        engine.dialog.value shouldBe null
    }

    /** The load clears only what it raised, or a chapter arriving would close the reader's own sheet. */
    @Test
    fun `the chapter arriving leaves a dialog the reader opened alone`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)
        provider.loadState.value = ReaderLoadState.Loading
        engine.openDialog(ReaderDialog.ChapterList)

        provider.loadState.value = ReaderLoadState.Idle

        engine.dialog.value shouldBe ReaderDialog.ChapterList
    }

    /**
     * Page actions travel as the capability built for that page, so the engine hands back the same
     * instance it was given. Reading the page back out of the slot is what this replaces.
     */
    @Test
    fun `page actions keep the capability they were raised with`() {
        val engine = engine()
        val actions = RecordingPageActions()

        engine.openDialog(ReaderDialog.PageActions(actions))

        val raised = engine.dialog.value as ReaderDialog.PageActions
        raised.actions shouldBeSameInstanceAs actions
    }

    @Test
    fun `a page action reaches the capability it was raised with`() {
        val engine = engine()
        val actions = RecordingPageActions()
        engine.openDialog(ReaderDialog.PageActions(actions))

        (engine.dialog.value as ReaderDialog.PageActions).actions.share(copyToClipboard = true)

        actions.shared shouldBe true
    }

    /**
     * The chrome names the entry whichever content type is open, so the engine must pass the
     * provider's answer through rather than hold one of its own that could go stale.
     */
    @Test
    fun `the chrome comes from the provider`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        provider.chrome.value = ReaderChromeState("Some Novel", "Chapter 2")

        engine.chrome.value shouldBe ReaderChromeState("Some Novel", "Chapter 2")
    }

    /**
     * The bar used to render manga's stored selection in every session, so a novel reader offered
     * reading mode and crop borders. The engine asking the provider is what stops that.
     */
    @Test
    fun `the bottom bar buttons come from the provider`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        provider.bottomButtons.value = setOf("as", "th")

        engine.bottomButtons.value shouldBe setOf("as", "th")
    }

    /**
     * The bar's verbs used to act on the manga model whatever was open, so rotating in a novel
     * session wrote a flag on a manga that was not there.
     */
    @Test
    fun `rotating goes to the session rather than a model the host picked`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        engine.setOrientation(ReaderOrientation.PORTRAIT.flagValue)

        provider.orientation.value shouldBe ReaderOrientation.PORTRAIT.flagValue
        engine.orientation.value shouldBe ReaderOrientation.PORTRAIT.flagValue
    }

    @Test
    fun `keeping the screen on goes to the session too`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        engine.setKeepScreenOn(true)

        provider.keepScreenOn.value shouldBe true
        engine.keepScreenOn.value shouldBe true
    }

    /**
     * The scrub reaches the installed viewport carrying the session's own unit, so a percentage can
     * never arrive somewhere that would read it as a page number.
     */
    @Test
    fun `seeking moves the installed viewport`() {
        val engine = engine()
        val viewport = FakeViewport()
        engine.installViewport(viewport)

        engine.seek(ChapterProgress.Percent(4200L))

        viewport.sought shouldBe ChapterProgress.Percent(4200L)
    }

    @Test
    fun `seeking with no viewport installed does nothing`() {
        engine().seek(ChapterProgress.Percent(4200L))
    }

    /**
     * The viewer is told after the step, not before: a viewer that keeps one long view of several
     * chapters would otherwise be pointed at the chapter it is leaving.
     */
    @Test
    fun `stepping a chapter tells the session first and then the viewport`() {
        val calls = mutableListOf<String>()
        val engine = engine(FakeReaderProvider(calls))
        engine.installViewport(FakeViewport(calls))

        engine.nextChapter()

        calls shouldBe listOf("session", "viewport")
    }

    @Test
    fun `stepping back goes back`() {
        val provider = FakeReaderProvider()
        val engine = engine(provider)

        engine.previousChapter()

        provider.stepped shouldBe -1
    }

    @Test
    fun `no viewport is installed to begin with`() {
        engine().viewport.value shouldBe null
    }

    @Test
    fun `installing puts the viewport in the slot`() {
        val engine = engine()
        val viewport = FakeViewport()

        engine.installViewport(viewport)

        engine.viewport.value shouldBeSameInstanceAs viewport
    }

    /**
     * Losing this step leaks the whole previous view tree, and nothing fails loudly when it happens,
     * which is why the engine owns it rather than the call site.
     */
    @Test
    fun `replacing destroys the outgoing viewport`() {
        val engine = engine()
        val first = FakeViewport()
        engine.installViewport(first)

        engine.installViewport(FakeViewport())

        first.destroyed shouldBe true
    }

    @Test
    fun `destroying clears the slot as well as the viewport`() {
        val engine = engine()
        val installed = FakeViewport()
        engine.installViewport(installed)

        engine.destroyViewport()

        installed.destroyed shouldBe true
        engine.viewport.value shouldBe null
    }

    /**
     * The manga shape. A session that offers no continuous scroll still has to answer the bar, and
     * answering false is what leaves the button off rather than lit over nothing.
     */
    @Test
    fun `a session without auto-scroll reports it off`() {
        val engine = engine()

        engine.autoScroll shouldBe null
        engine.autoScrollEnabled.value shouldBe false
    }

    @Test
    fun `auto-scroll follows the session that offers it`() {
        val provider = FakeReaderProvider()
        val auto = FakeAutoScroll()
        provider.autoScrollSlot = auto
        val engine = engine(provider)

        auto.toggle()

        engine.autoScrollEnabled.value shouldBe true
    }

    /** The bar draws the auto-scroll button only when the engine hands it a toggle to call. */
    @Test
    fun `a session that offers auto-scroll gets the button`() {
        val provider = FakeReaderProvider()
        val auto = FakeAutoScroll()
        provider.autoScrollSlot = auto

        engine(provider).autoScroll shouldBe auto
    }

    /** The manga shape again: an image has no words to bold, so the button is absent, not lit. */
    @Test
    fun `a session without bionic reading reports it off`() {
        val engine = engine()

        engine.bionicReading shouldBe null
        engine.bionicReadingEnabled.value shouldBe false
    }

    @Test
    fun `bionic reading follows the session that offers it`() {
        val provider = FakeReaderProvider()
        val bionic = FakeBionicReading()
        provider.bionicReadingSlot = bionic
        val engine = engine(provider)

        bionic.toggle()

        engine.bionicReadingEnabled.value shouldBe true
    }

    /** The novel shape, and the half the absent case cannot catch: the bar's button needs the toggle. */
    @Test
    fun `a session that offers bionic reading gets the button`() {
        val provider = FakeReaderProvider()
        val bionic = FakeBionicReading()
        provider.bionicReadingSlot = bionic

        engine(provider).bionicReading shouldBe bionic
    }

    /**
     * A scrub is an explicit position choice, and a running auto-scroll would carry the reader off it
     * within a frame, so the engine stops it before the viewport moves.
     */
    @Test
    fun `scrubbing stops a running auto-scroll`() {
        val provider = FakeReaderProvider()
        val auto = FakeAutoScroll()
        provider.autoScrollSlot = auto
        val engine = engine(provider)
        engine.installViewport(FakeViewport())
        auto.toggle()

        engine.seek(ChapterProgress.Percent(hundredths = 5000))

        engine.autoScrollEnabled.value shouldBe false
    }

    /**
     * The provider only starts the load, so without this the sheet moved the model and the app bar to
     * the picked chapter while the viewer kept rendering whatever page it was already on.
     */
    @Test
    fun `picking a chapter lands the viewport on it once the session holds it`() = runTest(scheduler) {
        val provider = FakeReaderProvider()
        val engine = engine(provider)
        val viewport = FakeViewport()
        engine.installViewport(viewport)

        engine.chapterList.open(7L)
        advanceUntilIdle()

        viewport.chapterOpens shouldBe 1
    }

    /** A chapter that never becomes current must not move the reader onto whatever is showing instead. */
    @Test
    fun `a pick the session never lands on moves nothing`() = runTest(scheduler) {
        val provider = FakeReaderProvider()
        val engine = engine(provider)
        val viewport = FakeViewport()
        engine.installViewport(viewport)

        engine.chapterList.open(FakeChapterList.NEVER_LOADS)
        advanceUntilIdle()

        viewport.chapterOpens shouldBe 0
    }

    /** The sheet's other verbs are the provider's own, so wrapping open must not swallow them. */
    @Test
    fun `the sheet's remaining verbs still reach the provider`() {
        val provider = FakeReaderProvider()

        engine(provider).chapterList.setRead(7L, read = true)

        provider.chapterList.readMarks shouldBe 1
    }

    /** A session with no auto-scroll still scrubs, rather than the engine reaching through a null. */
    @Test
    fun `scrubbing a session without auto-scroll still moves the viewport`() {
        val engine = engine()
        val viewport = FakeViewport()
        engine.installViewport(viewport)

        engine.seek(ChapterProgress.Percent(hundredths = 5000))

        viewport.sought shouldBe ChapterProgress.Percent(hundredths = 5000)
    }
}

private class FakeBionicReading : ReaderBionicReading {
    override val enabled = MutableStateFlow(false)

    override fun toggle() {
        enabled.value = !enabled.value
    }
}

private class FakeAutoScroll : ReaderAutoScroll {
    override val enabled = MutableStateFlow(false)

    override fun toggle() {
        enabled.value = !enabled.value
    }

    override fun stop() {
        enabled.value = false
    }
}

/**
 * The engine only ever holds the provider for the host to build through, and no test here builds a
 * viewport, so this never has to answer. Building is the half that needs a real Activity.
 */
private class FakeReaderProvider(
    /** Shared with [FakeViewport] so the order the engine did the two in is readable, which counting
     *  each side separately cannot show. */
    private val calls: MutableList<String> = mutableListOf(),
) : ReaderProvider {
    override val chrome = MutableStateFlow(ReaderChromeState())

    override val bottomButtons = MutableStateFlow(emptySet<String>())

    override val orientation = MutableStateFlow(0)

    override val keepScreenOn = MutableStateFlow(false)

    override val textSettings: ReaderTextSettings? = null

    /** Set by the test that needs a session offering it; null is the manga shape. */
    var autoScrollSlot: ReaderAutoScroll? = null

    override val autoScroll: ReaderAutoScroll? get() = autoScrollSlot

    var bionicReadingSlot: ReaderBionicReading? = null

    override val bionicReading: ReaderBionicReading? get() = bionicReadingSlot

    override val navigator = MutableStateFlow(ReaderNavigatorState())

    override val showProgress = MutableStateFlow(false)

    override val loadState = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Idle)

    override fun retryLoad() {
        retried++
    }

    var retried = 0
        private set

    override val bookmarked = MutableStateFlow(false)

    override val webUrl = MutableStateFlow<String?>(null)

    override fun toggleBookmark() {
        bookmarked.value = !bookmarked.value
    }

    override fun setOrientation(flagValue: Int) {
        orientation.value = flagValue
    }

    var stepped = 0
        private set

    override suspend fun previousChapter() {
        stepped--
        calls += "session"
    }

    override suspend fun nextChapter() {
        stepped++
        calls += "session"
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOn.value = enabled
    }

    override val chapterList = FakeChapterList()

    override fun createViewport(host: ReaderActivity): ReaderViewport =
        error("a unit test never builds a viewport")
}

/** Opening reports the chapter as current, the way a load that finished would. */
private class FakeChapterList : ReaderChapterList {
    override val rows = MutableStateFlow(emptyList<ReaderChapterRow>())

    override val currentChapterId = MutableStateFlow(-1L)

    var readMarks = 0
        private set

    override fun open(chapterId: Long) {
        if (chapterId != NEVER_LOADS) currentChapterId.value = chapterId
    }

    override fun setRead(chapterId: Long, read: Boolean) {
        readMarks++
    }

    override fun setBookmark(chapterId: Long, bookmarked: Boolean) = Unit

    override fun download(chapterId: Long, action: ChapterDownloadAction) = Unit

    companion object {
        /** The id whose load never finishes, standing in for a chapter that failed to open. */
        const val NEVER_LOADS = 99L
    }
}

private class FakeViewport(private val calls: MutableList<String> = mutableListOf()) : ReaderViewport {
    var sought: ChapterProgress? = null
        private set

    var destroyed = false
        private set

    override val view: View get() = error("no view in a unit test")

    override val isRtl = false

    override fun destroy() {
        destroyed = true
    }

    override fun handleKeyEvent(event: KeyEvent) = false

    override fun handleGenericMotionEvent(event: MotionEvent) = false

    override fun onChapterStepped() {
        calls += "viewport"
    }

    var chapterOpens = 0
        private set

    override fun onChapterOpened() {
        chapterOpens++
    }

    override fun seekTo(progress: ChapterProgress) {
        sought = progress
    }
}

private class RecordingPageActions : ReaderPageActions {
    var shared = false
        private set

    override fun save() = Unit

    override fun share(copyToClipboard: Boolean) {
        shared = true
    }

    override fun setAsCover() = Unit
}
