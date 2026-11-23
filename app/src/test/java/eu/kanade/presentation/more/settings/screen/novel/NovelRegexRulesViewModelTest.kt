package eu.kanade.presentation.more.settings.screen.novel

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.NovelRegexReplacement
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The rule editor asks for a preview on every keystroke, so the answer comes back as state, off the main thread. */
class NovelRegexRulesViewModelTest {

    private lateinit var model: NovelRegexRulesViewModel

    private val rule =
        NovelRegexReplacement(title = "", pattern = "cat", replacement = "dog", isRegex = false, id = "r")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        model = NovelRegexRulesViewModel(NovelPreferences(InMemoryPreferenceStore()))
    }

    @AfterEach
    fun tearDown() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a preview answers for the rule and sample asked`() {
        // Ended on the Main it was built on, since tearDown's cancel would reach Main once reset.
        model.viewModelScope.cancel()
        virtualDispatchers()
        try {
            runTest {
                // Built on the virtual dispatchers, so the settle and the move off Main run in virtual time.
                val virtual = NovelRegexRulesViewModel(NovelPreferences(InMemoryPreferenceStore()))
                virtual.askPreview(rule, "a cat")

                val answered = virtual.preview.filterNotNull().first().also { virtual.close() }

                answered shouldBe NovelRegexPreview(rule, "a cat", Result.success("a dog"))
            }
        } finally {
            resetVirtualDispatchers()
        }
    }

    /**
     * Real time, as in the next test: the run is a blocking wait on the regex runner's own thread, timed on the
     * monotonic clock, which one virtual-time thread cannot both hold and move past.
     *
     * Main is a real thread here, asked for a turn while a runaway pattern runs out its budget: on Main, the
     * run held it for the rest of that budget. The pattern is new per run, since one that ran too long is
     * skipped from then on.
     */
    @Test
    fun `a runaway pattern's preview leaves the main thread free`() = runTest {
        model.close()
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val onRealMain = NovelRegexRulesViewModel(NovelPreferences(InMemoryPreferenceStore()))
        val runaway = rule.copy(pattern = "(.*i){12}j${System.nanoTime()}", isRegex = true)

        val mainWait = withContext(Dispatchers.Default) {
            val answered = async { onRealMain.preview.filterNotNull().first() }
            onRealMain.askPreview(runaway, "i".repeat(40))
            delay(RUN_UNDER_WAY)
            measureTime { withContext(Dispatchers.Main) {} }
                .also { withTimeout(5.seconds) { answered.await() } }
        }
        onRealMain.close()
        main.close()

        // Under the ~400 ms a held Main would wait out the budget, with room for a busy runner's hop.
        mainWait shouldBeLessThan 300.milliseconds
    }

    /**
     * A newer edit replaces a run still matching rather than waiting out its budget, and the replaced run says
     * nothing: its cancellation is not the pattern's error.
     */
    @Test
    fun `a preview a newer edit replaces is never answered`() = runTest {
        val slow = rule.copy(pattern = "(.*i){12}j${System.nanoTime()}", isRegex = true)

        val seen = withContext(Dispatchers.Default) {
            // Collected to the newer answer by the one subscriber, so nothing it saw can be cut off before it lands.
            val answers = async(start = CoroutineStart.UNDISPATCHED) {
                model.preview.filterNotNull().transformWhile {
                    emit(it)
                    it.rule != rule
                }.toList()
            }
            model.askPreview(slow, "i".repeat(40))
            delay(RUN_UNDER_WAY)
            model.askPreview(rule, "a cat")
            withTimeout(5.seconds) { answers.await() }.also { model.close() }
        }

        seen.map { it.rule } shouldBe listOf(rule)
    }

    /** Waits the scope's work out too, since a run still ending on Default would otherwise reach Main after reset. */
    private suspend fun NovelRegexRulesViewModel.close() = viewModelScope.coroutineContext.job.cancelAndJoin()

    private companion object {
        /** Past the 250 ms settle, early enough in the 500 ms budget for an edit made now to settle first. */
        val RUN_UNDER_WAY = 350.milliseconds
    }
}
