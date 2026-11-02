package reikai.presentation.reader.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
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

    private fun webGpu(vertical: Boolean) = mockk<WebGpuViewer>(relaxed = true) {
        every { isContinuous } returns false
        every { isVertical } returns vertical
    }

    private fun openTab(title: String) = compose.onNodeWithContentDescription(title).performClick()

    @Test
    fun `the view renderer offers grayscale`() {
        show(null)
        openTab("Filters")

        compose.onNodeWithText("Grayscale").assertExists()
    }

    @Test
    fun `the WebGPU renderer offers no grayscale or inverted colours`() {
        show(webGpu(vertical = false))
        openTab("Filters")

        compose.onNodeWithText("Grayscale").assertDoesNotExist()
        compose.onNodeWithText("Inverted").assertDoesNotExist()
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
}
