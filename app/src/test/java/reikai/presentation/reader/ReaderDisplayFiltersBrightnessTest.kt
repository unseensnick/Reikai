package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

class ReaderDisplayFiltersBrightnessTest {

    private val preferences = ReaderPreferences(EmittingPreferenceStore())
    private val filters = ReaderDisplayFilters(
        customBrightness = preferences.customBrightness,
        customBrightnessValue = preferences.customBrightnessValue,
        colorFilter = preferences.colorFilter,
        colorFilterValue = preferences.colorFilterValue,
        colorFilterMode = preferences.colorFilterMode,
        grayscale = preferences.grayscale,
        invertedColors = preferences.invertedColors,
    )
    private val applied = mutableListOf<Int>()

    private fun TestScope.follow() {
        backgroundScope.launch { filters.brightness().collect { applied += it } }
        settle()
    }

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
    }

    @Test
    fun `brightness that is off is applied as none`() = runTest {
        follow()

        applied.last() shouldBe 0
    }

    @Test
    fun `brightness that is on follows its value`() = runTest {
        preferences.customBrightness.set(true)
        follow()

        preferences.customBrightnessValue.set(40)
        settle()

        applied.last() shouldBe 40
    }

    @Test
    fun `a value moved after brightness was turned off is not applied`() = runTest {
        preferences.customBrightness.set(true)
        follow()
        preferences.customBrightness.set(false)
        settle()

        preferences.customBrightnessValue.set(40)
        settle()

        applied.last() shouldBe 0
    }
}
