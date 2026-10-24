package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonConfig
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * The tap zones a manga viewer opens with. Each zone rebuild tells the overlay, and the overlay fades in on every
 * call after the first, so opening must rebuild once. Webtoon's config stands in for all three, which share the
 * rule through [ViewerConfig]. Robolectric, because a zone is a RectF.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerConfigTapZonesTest {

    private val preferences = ReaderPreferences(EmittingPreferenceStore())
    private var calls = 0

    private fun TestScope.openViewer(): WebtoonConfig =
        WebtoonConfig(backgroundScope, preferences).also {
            it.navigationModeChangedListener = { calls++ }
            runCurrent()
        }

    @Test
    fun `opening a viewer hands the overlay its zones once`() = runTest {
        openViewer()

        calls shouldBe 1
    }

    @Test
    fun `Bottom opens at the stored height`() = runTest {
        preferences.tapBottomZoneHeight.set(30)
        preferences.navigationModeWebtoon.set(BOTTOM)

        val config = openViewer()

        config.navigator.getRegions().single().rectF.top shouldBe (0.7f plusOrMinus 0.0001f)
    }

    @Test
    fun `a new height rebuilds the Bottom zone`() = runTest {
        preferences.navigationModeWebtoon.set(BOTTOM)
        val config = openViewer()

        preferences.tapBottomZoneHeight.set(30)
        runCurrent()

        config.navigator.getRegions().single().rectF.top shouldBe (0.7f plusOrMinus 0.0001f)
    }

    private companion object {
        const val BOTTOM = 9
    }
}
