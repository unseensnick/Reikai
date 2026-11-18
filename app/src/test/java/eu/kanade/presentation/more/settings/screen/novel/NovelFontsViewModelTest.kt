package eu.kanade.presentation.more.settings.screen.novel

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
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
import reikai.novel.font.NovelFont
import reikai.novel.font.NovelFontManager
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlin.time.Duration.Companion.seconds

/** A font write is a write a closed screen must not cut off. */
class NovelFontsViewModelTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `deleting a font finishes after the screen closes`() = runTest {
        val font = NovelFont("Lora.ttf", "Lora")
        val deleted = CompletableDeferred<Unit>()
        val fonts = mockk<NovelFontManager> {
            coEvery { installed() } returns emptyList()
            coEvery { delete(font) } coAnswers {
                delay(100)
                deleted.complete(Unit)
                true
            }
        }
        val appScope = testAppScope()
        val model = NovelFontsViewModel(fonts, NovelPreferences(InMemoryPreferenceStore()), appScope)

        model.delete(font)
        model.viewModelScope.cancel()

        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { deleted.await() }.also { appScope.settle() }
        } shouldBe
            Unit
    }

    @Test
    fun `a download finishes after the screen closes`() = runTest {
        val font = NovelFont("Lora.ttf", "Lora")
        val fonts = mockk<NovelFontManager> {
            coEvery { installed() } returns emptyList()
            coEvery { download("Lora") } coAnswers {
                delay(100)
                Result.success(font)
            }
        }
        val appScope = testAppScope()
        val model = NovelFontsViewModel(fonts, NovelPreferences(InMemoryPreferenceStore()), appScope)

        model.download("Lora")
        model.viewModelScope.cancel()

        val settled = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { model.state.first { !it.busy } }.also { appScope.settle() }
        }

        settled.error shouldBe null
    }

    /**
     * Waits out what the write still has to run (its refresh) before tearDown resets Main, since a refresh
     * reaching Main after the reset fails the next test in this JVM.
     */
    private suspend fun CoroutineScope.settle() {
        val running = this.coroutineContext.job.children.toList()
        withTimeout(5.seconds) { running.joinAll() }
    }
}
