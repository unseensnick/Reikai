package reikai.presentation.reader.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.reader.ReaderDisplayFilters
import reikai.presentation.recents.EmittingPreferenceStore

/** The rows the manga sheet offers depend on the running viewer: none may be a control that does nothing. */
@RunWith(RobolectricTestRunner::class)
class MangaReaderSheetRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = ReaderPreferences(EmittingPreferenceStore())

    private fun show(viewer: Viewer?) {
        val model = ReaderSettingsViewModel(
            readerState = MutableStateFlow(ReaderViewModel.State()),
            onChangeReadingMode = {},
            onChangeOrientation = {},
            preferences = preferences,
        )
        val filters = ReaderDisplayFilters(
            customBrightness = preferences.customBrightness,
            customBrightnessValue = preferences.customBrightnessValue,
            colorFilter = preferences.colorFilter,
            colorFilterValue = preferences.colorFilterValue,
            colorFilterMode = preferences.colorFilterMode,
            grayscale = preferences.grayscale,
            invertedColors = preferences.invertedColors,
            contrastPercent = preferences.contrast,
        )
        compose.setContent {
            ComposeTestHost {
                ReaderSettingsSheet(
                    pages = ReaderSettingsPages.Manga(model, filters, MutableStateFlow(viewer)),
                    autoScrollRunning = false,
                    onToggleAutoScroll = {},
                    autoScroll = null,
                    onDismissRequest = {},
                    onShowMenus = {},
                    onHideMenus = {},
                )
            }
        }
    }

    private fun webGpu(vertical: Boolean = false, continuous: Boolean = false) = mockk<WebGpuViewer>(relaxed = true) {
        every { isContinuous } returns continuous
        every { isVertical } returns vertical
    }

    private fun openTab(title: String) = compose.onNodeWithContentDescription(title).performClick()

    @Test
    fun `the view renderer offers grayscale`() {
        show(null)
        openTab("Filters")

        compose.onNodeWithText("Grayscale").assertExists()
    }

    /** The high quality renderer applies them as its page table, so they show there as everywhere. */
    @Test
    fun `the WebGPU renderer offers grayscale`() {
        show(webGpu(vertical = false))
        openTab("Filters")

        compose.onNodeWithText("Grayscale").assertExists()
    }

    @Test
    fun `the WebGPU renderer offers inverted colours`() {
        show(webGpu(vertical = false))
        openTab("Filters")

        compose.onNodeWithText("Inverted").assertExists()
    }

    /** Dual pages stay on in this mode, and with no row here nothing could turn them off. */
    @Test
    fun `WebGPU paged vertical offers dual page view`() {
        show(webGpu(vertical = true))

        compose.onNodeWithText("Dual page view").assertExists()
    }

    /** A restored backup takes any number, and the viewers read one out of range as Fit screen. */
    @Test
    fun `a stored scale type out of range still draws the sheet`() {
        preferences.imageScaleType.set(7)

        show(null)

        compose.onNodeWithText("For this series").assertExists()
    }

    @Test
    fun `WebGPU single page offers disabling double tap`() {
        show(webGpu())
        openTab("Controls")

        compose.onNodeWithText("Disable double tap gestures").assertExists()
    }

    @Test
    fun `WebGPU dual page offers disabling double tap`() {
        preferences.dualPageView.set(ReaderPreferences.DualPageView.ALWAYS)

        show(webGpu())
        openTab("Controls")

        compose.onNodeWithText("Disable double tap gestures").assertExists()
    }

    @Test
    fun `WebGPU long strip offers disabling double tap`() {
        show(webGpu(continuous = true))
        openTab("Controls")

        compose.onNodeWithText("Disable double tap gestures").assertExists()
    }

    /** The long strip crops by its own preference; the paged one would change nothing there. */
    @Test
    fun `WebGPU long strip crop borders sets the long strip preference`() {
        show(webGpu(continuous = true))

        compose.onNodeWithText("Crop borders").performSemanticsAction(SemanticsActions.OnClick)

        preferences.cropBordersWebtoon.get() shouldBe true
    }

    /** Its own Transition animation, which has None, turns the page. */
    @Test
    fun `WebGPU paged leaves page transitions to its transition animation`() {
        show(webGpu())
        openTab("Appearance")

        compose.onNodeWithText("Animate page transitions").assertDoesNotExist()
    }

    @Test
    fun `WebGPU long strip offers page transitions`() {
        show(webGpu(continuous = true))
        openTab("Appearance")

        compose.onNodeWithText("Animate page transitions").assertExists()
    }

    @Test
    fun `WebGPU long strip split sets the long strip preference`() {
        show(webGpu(continuous = true))

        compose.onNodeWithText("Split wide pages").performSemanticsAction(SemanticsActions.OnClick)

        preferences.dualPageSplitWebtoon.get() shouldBe true
    }

    @Test
    fun `WebGPU paged rotate sets the paged preference`() {
        show(webGpu())

        compose.onNodeWithText("Rotate wide pages to fit").performSemanticsAction(SemanticsActions.OnClick)

        preferences.dualPageRotateToFit.get() shouldBe true
    }

    @Test
    fun `WebGPU dual page leaves wide pages whole`() {
        preferences.dualPageView.set(ReaderPreferences.DualPageView.ALWAYS)

        show(webGpu())

        compose.onNodeWithText("Split wide pages").assertDoesNotExist()
    }

    @Test
    fun `WebGPU paged offers smart fit`() {
        show(webGpu())

        compose.onNodeWithText("Smart fit").assertExists()
    }

    @Test
    fun `WebGPU paged offers no stretch`() {
        show(webGpu())

        compose.onNodeWithText("Stretch").assertDoesNotExist()
    }

    @Test
    fun `WebGPU paged rows follow a dual page view change`() {
        show(webGpu())

        preferences.dualPageView.set(ReaderPreferences.DualPageView.ALWAYS)

        compose.onNodeWithText("Crop borders").assertDoesNotExist()
    }
}
