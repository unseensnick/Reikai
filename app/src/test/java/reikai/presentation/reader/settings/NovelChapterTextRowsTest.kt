package reikai.presentation.reader.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.presentation.ComposeTestHost
import reikai.presentation.reader.ReaderDisplayFilters
import reikai.presentation.reader.ReaderTextSettings
import reikai.presentation.reader.ReaderTextState
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * The sheet's Chapter text rows follow the renderer the open novel uses, which its own choice sets over
 * the default. Novel-only: a manga page has no renderer to choose.
 */
@RunWith(RobolectricTestRunner::class)
class NovelChapterTextRowsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a novel kept on the WebView renderer offers its chapter styling over a native default`() {
        val preferences = NovelPreferences(EmittingPreferenceStore())
        compose.setContent {
            ComposeTestHost {
                ReaderSettingsSheet(
                    pages = ReaderSettingsPages.Novel(
                        preferences = preferences,
                        textSettings = OwnMode(NovelRenderingMode.WEBVIEW),
                        filters = filters(preferences),
                        orientation = flowOf(0),
                        onChangeOrientation = {},
                        installedFonts = { emptyList() },
                    ),
                    autoScrollRunning = false,
                    onToggleAutoScroll = {},
                    autoScroll = null,
                    onDismissRequest = {},
                    onShowMenus = {},
                    onHideMenus = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Appearance").performClick()

        compose.onNodeWithText("Use the chapter's own styling").assertExists()
    }

    private fun filters(preferences: NovelPreferences) = ReaderDisplayFilters(
        customBrightness = preferences.readerCustomBrightness(),
        customBrightnessValue = preferences.readerCustomBrightnessValue(),
        colorFilter = preferences.readerColorFilter(),
        colorFilterValue = preferences.readerColorFilterValue(),
        colorFilterMode = preferences.readerColorFilterMode(),
        grayscale = preferences.readerGrayscale(),
        invertedColors = preferences.readerInvertedColors(),
        contrastPercent = preferences.readerContrast(),
    )

    private class OwnMode(mode: NovelRenderingMode) : ReaderTextSettings {
        override val state = MutableStateFlow(
            ReaderTextState(
                fontSize = 18,
                followSystemTheme = true,
                backgroundColor = "#000000",
                textColor = "#FFFFFF",
            ),
        )
        override fun setFontSize(size: Int) = Unit
        override fun followSystemTheme() = Unit
        override fun setThemeColors(background: String, textColor: String) = Unit

        override val renderingMode: Flow<NovelRenderingMode?> = flowOf(mode)

        override fun setRenderingMode(mode: NovelRenderingMode?) = Unit
    }
}
