package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.ui.geometry.Offset
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.reader.navigation.MangaTapLayouts
import reikai.presentation.reader.navigation.actionAt
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * The tap zones a manga viewer opens with. Each zone rebuild tells the overlay, and the overlay fades in on every
 * call after the first, so opening must rebuild once. The long strip's settings stand in for the pager's, which
 * build their zones through the same [NavigationSettings].
 */
@RunWith(RobolectricTestRunner::class)
class ViewerSettingsTapZonesTest {

    private val preferences = ReaderPreferences(EmittingPreferenceStore())

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ReaderSettingsViewModel(
        readerState = MutableStateFlow(ReaderViewModel.State()),
        onChangeReadingMode = {},
        onChangeOrientation = {},
        preferences = preferences,
    )

    private fun TestScope.openViewer(): ReaderSettingsViewModel = viewModel().also {
        backgroundScope.launch { it.simpleLongStrip.collect() }
        runCurrent()
    }

    private fun ReaderSettingsViewModel.zones() = simpleLongStrip.value.navigation.navigator(false, ::LNavigation)

    private fun TestScope.zonesOf(mode: ReadingMode, isWebGpu: Boolean = false): ViewerNavigation? {
        val settings = openViewer()
        backgroundScope.launch { settings.navigation.collect() }
        settings.setViewerReadingMode(mode, isWebGpu)
        runCurrent()
        return settings.navigation.value
    }

    @Test
    fun `opening a viewer hands the overlay its zones once`() = runTest {
        val settings = openViewer()
        val zones = mutableListOf<ViewerNavigation?>()
        backgroundScope.launch { settings.navigation.collect(zones::add) }

        settings.setViewerReadingMode(ReadingMode.WEBTOON, isWebGpu = false)
        runCurrent()

        zones.filterNotNull() shouldHaveSize 1
    }

    @Test
    fun `a new height reaches the running viewer's zones`() = runTest {
        preferences.navigationModeWebtoon.set(BOTTOM)
        val settings = openViewer()
        backgroundScope.launch { settings.navigation.collect() }
        settings.setViewerReadingMode(ReadingMode.WEBTOON, isWebGpu = false)
        runCurrent()

        preferences.tapBottomZoneHeight.set(30)
        runCurrent()

        settings.navigation.value!!.getRegions().single().rect.top shouldBe (0.7f plusOrMinus 0.0001f)
    }

    @Test
    fun `the view renderer's strip reads the strip's own layout`() = runTest {
        preferences.navigationModeWebtoon.set(DISABLED)

        zonesOf(ReadingMode.WEBTOON).shouldBeInstanceOf<DisabledNavigation>()
    }

    @Test
    fun `the WebGPU renderer's strip reads the paged layout`() = runTest {
        preferences.navigationModePager.set(DISABLED)

        zonesOf(ReadingMode.WEBTOON, isWebGpu = true).shouldBeInstanceOf<DisabledNavigation>()
    }

    @Test
    fun `a vertical mode defaults to L`() = runTest {
        zonesOf(ReadingMode.VERTICAL).shouldBeInstanceOf<LNavigation>()
    }

    @Test
    fun `a horizontal mode defaults to right and left`() = runTest {
        zonesOf(ReadingMode.LEFT_TO_RIGHT).shouldBeInstanceOf<RightAndLeftNavigation>()
    }

    @Test
    fun `a right to left book reverses its zones`() = runTest {
        val zones = zonesOf(ReadingMode.RIGHT_TO_LEFT)!!

        zones.actionAt(Offset(0.1f, 0.5f)) shouldBe ViewerNavigation.Action.FORWARD
    }

    @Test
    fun `Bottom opens at the stored height`() = runTest {
        preferences.tapBottomZoneHeight.set(30)
        preferences.navigationModeWebtoon.set(BOTTOM)

        val settings = openViewer()

        settings.zones().getRegions().single().rect.top shouldBe (0.7f plusOrMinus 0.0001f)
    }

    @Test
    fun `a new height rebuilds the Bottom zone`() = runTest {
        preferences.navigationModeWebtoon.set(BOTTOM)
        val settings = openViewer()

        preferences.tapBottomZoneHeight.set(30)
        runCurrent()

        settings.zones().getRegions().single().rect.top shouldBe (0.7f plusOrMinus 0.0001f)
    }

    @Test
    fun `a pager opens a layout upstream has no entry for`() = runTest {
        preferences.tapBottomZoneHeight.set(30)
        preferences.navigationModePager.set(BOTTOM)

        val zones = viewModel().paged.value.navigation.navigator(false, ::LNavigation)

        zones.getRegions().single().rect.top shouldBe (0.7f plusOrMinus 0.0001f)
    }

    @Test
    fun `an unknown stored layout falls back to the viewer's default`() {
        val zones = NavigationSettings(
            99,
            ReaderPreferences.TappingInvertMode.NONE,
            30,
        ).navigator(false, ::DisabledNavigation)

        zones.shouldBeInstanceOf<DisabledNavigation>()
    }

    @Test
    fun `a new volume key distance reaches the long strip without remaking its pages`() = runTest {
        val settings = openViewer()
        val page = settings.simpleLongStrip.value.page

        preferences.readWithVolumeKeysScrollAmount.set(0.3f)
        runCurrent()

        settings.simpleLongStrip.value.volumeKeysScrollFraction shouldBe 0.3f
        settings.simpleLongStrip.value.page shouldBe page
    }

    private companion object {
        const val BOTTOM = 9
        const val DISABLED = MangaTapLayouts.DISABLED
    }
}
