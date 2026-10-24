package reikai.presentation.reader.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.novel.NovelPreferences
import reikai.presentation.ComposeTestHost
import reikai.presentation.reader.ReaderDisplayFilters
import reikai.presentation.reader.ReaderTextSettings
import reikai.presentation.reader.ReaderTextState
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * The reader's one settings sheet over each reader's page set: every shared tab shows its rows for
 * both, and only a novel gets Read aloud. Read aloud itself needs a TTS engine, so a device checks it.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ReaderSettingsSheetTest(private val probe: SheetProbe) {

    @get:Rule
    val compose = createComposeRule()

    private fun showSheet() = compose.setContent {
        ComposeTestHost {
            ReaderSettingsSheet(
                pages = probe.pages(),
                autoScrollRunning = false,
                onToggleAutoScroll = {},
                autoScroll = null,
                onDismissRequest = {},
                onShowMenus = {},
                onHideMenus = {},
            )
        }
    }

    private fun openTab(title: String) = compose.onNodeWithContentDescription(title).performClick()

    @Test
    fun `the sheet offers a tab per page`() {
        showSheet()

        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertCountEquals(probe.tabCount)
    }

    @Test
    fun `the reading tab shows this series' settings`() {
        showSheet()

        compose.onNodeWithText("For this series").assertExists()
    }

    @Test
    fun `the appearance tab shows the page rows`() {
        showSheet()
        openTab("Appearance")

        compose.onNodeWithText("Keep screen on").assertExists()
    }

    @Test
    fun `the controls tab shows auto-scroll`() {
        showSheet()
        openTab("Controls")

        compose.onNodeWithText("Auto-scroll").assertExists()
    }

    @Test
    fun `the filters tab shows the colour filters`() {
        showSheet()
        openTab("Filters")

        compose.onNodeWithText("Custom brightness").assertExists()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun probes(): List<Array<Any>> = listOf(arrayOf(MangaSheetProbe()), arrayOf(NovelSheetProbe()))
    }
}

/** One reader's page set for the sheet, built from preferences in memory. */
interface SheetProbe {
    val tabCount: Int
    fun pages(): ReaderSettingsPages
}

class MangaSheetProbe : SheetProbe {
    override val tabCount = 4
    override fun toString() = "manga"

    override fun pages(): ReaderSettingsPages {
        val preferences = ReaderPreferences(EmittingPreferenceStore())
        val viewModel = ReaderSettingsViewModel(
            readerState = MutableStateFlow(ReaderViewModel.State()),
            viewerState = MutableStateFlow(null),
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
        return ReaderSettingsPages.Manga(viewModel, filters)
    }
}

class NovelSheetProbe : SheetProbe {
    override val tabCount = 5
    override fun toString() = "novels"

    override fun pages(): ReaderSettingsPages {
        val preferences = NovelPreferences(EmittingPreferenceStore())
        val filters = ReaderDisplayFilters(
            customBrightness = preferences.readerCustomBrightness(),
            customBrightnessValue = preferences.readerCustomBrightnessValue(),
            colorFilter = preferences.readerColorFilter(),
            colorFilterValue = preferences.readerColorFilterValue(),
            colorFilterMode = preferences.readerColorFilterMode(),
            grayscale = preferences.readerGrayscale(),
            invertedColors = preferences.readerInvertedColors(),
        )
        return ReaderSettingsPages.Novel(
            preferences = preferences,
            textSettings = FixedTextSettings,
            filters = filters,
            orientation = flowOf(0),
            onChangeOrientation = {},
            installedFonts = { emptyList() },
        )
    }
}

private object FixedTextSettings : ReaderTextSettings {
    override val state = MutableStateFlow(
        ReaderTextState(fontSize = 18, followSystemTheme = true, backgroundColor = "#000000", textColor = "#FFFFFF"),
    )
    override fun setFontSize(size: Int) = Unit
    override fun followSystemTheme() = Unit
    override fun setThemeColors(background: String, textColor: String) = Unit
}
