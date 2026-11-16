package eu.kanade.presentation.more.settings.screen.novel

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
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
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.NovelRegexReplacement
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    fun `a preview answers for the rule and sample asked`() = runTest {
        model.askPreview(rule, "a cat")

        val answered = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { model.preview.filterNotNull().first() }
        }

        answered shouldBe NovelRegexPreview(rule, "a cat", Result.success("a dog"))
    }

    @Test
    fun `asking for a preview of a runaway pattern does not wait on the match`() {
        val runaway = rule.copy(pattern = "(.*i){12}j", isRegex = true)
        val started = TimeSource.Monotonic.markNow()

        model.askPreview(runaway, "i".repeat(40))

        started.elapsedNow() shouldBeLessThan 100.milliseconds
    }
}
