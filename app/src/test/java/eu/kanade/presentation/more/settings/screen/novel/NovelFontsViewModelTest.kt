package eu.kanade.presentation.more.settings.screen.novel

import androidx.lifecycle.viewModelScope
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.novel.font.NovelFont
import reikai.novel.font.NovelFontManager
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.testAppScope
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** A font write is a write a closed screen must not cut off. */
class NovelFontsViewModelTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

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
        val model = NovelFontsViewModel(fonts, NovelPreferences(InMemoryPreferenceStore()), testAppScope())
        restubDefaultDispatcher()

        model.delete(font)
        model.viewModelScope.cancel()
        advanceUntilIdle()

        deleted.isCompleted shouldBe true
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
        val model = NovelFontsViewModel(fonts, NovelPreferences(InMemoryPreferenceStore()), testAppScope())
        restubDefaultDispatcher()

        model.download("Lora")
        model.viewModelScope.cancel()
        advanceUntilIdle()

        model.state.value.let { it.busy to it.error } shouldBe (false to null)
    }
}
