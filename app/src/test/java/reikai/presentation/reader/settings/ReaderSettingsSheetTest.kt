package reikai.presentation.reader.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
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

    private fun showSheet(pages: ReaderSettingsPages = probe.pages()) = compose.setContent {
        ComposeTestHost {
            ReaderSettingsSheet(
                pages = pages,
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

    /** The series' own choices, whose Default follows the reader's settings, so the default gets no second row. */
    @Test
    fun `the reading tab shows each of the series' modes once`() {
        showSheet()

        val counts = probe.seriesModeRows.associateWith {
            compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().size
        }

        counts shouldBe probe.seriesModeRows.associateWith { 1 }
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

    /** Shown in every viewer, the high quality renderer included, which applies it as its page table. */
    @Test
    fun `the filters tab offers contrast`() {
        showSheet()
        openTab("Filters")

        compose.onNodeWithText("Contrast").assertExists()
    }

    @Test
    fun `the controls tab offers the volume keys`() {
        showSheet()
        openTab("Controls")

        compose.onNodeWithText("Volume keys").assertExists()
    }

    @Test
    fun `a scrolling reader offers the volume key scroll amount`() {
        showSheet(probe.volumeKeyPages(paged = false))
        openTab("Controls")

        compose.onNodeWithText("Volume key scroll amount").assertExists()
    }

    /** A press turns a whole page there, so there is no distance to choose. */
    @Test
    fun `a paged reader offers no volume key scroll amount`() {
        showSheet(probe.volumeKeyPages(paged = true))
        openTab("Controls")

        compose.onNodeWithText("Volume key scroll amount").assertDoesNotExist()
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
    val seriesModeRows: List<String>
    fun pages(): ReaderSettingsPages

    /** The pages with the volume keys on, over a reader that turns pages when [paged], else scrolls. */
    fun volumeKeyPages(paged: Boolean): ReaderSettingsPages
}

class MangaSheetProbe : SheetProbe {
    override val tabCount = 4
    override val seriesModeRows = listOf("Reading mode")
    override fun toString() = "manga"

    override fun pages(): ReaderSettingsPages = pages(ReaderPreferences(EmittingPreferenceStore()), viewer = null)

    // The classic long strip is the scrolling viewer; no viewer reads as a paged one.
    override fun volumeKeyPages(paged: Boolean): ReaderSettingsPages {
        val preferences = ReaderPreferences(EmittingPreferenceStore())
        preferences.readWithVolumeKeys.set(true)
        return pages(preferences, viewer = if (paged) null else mockk<WebtoonViewer>(relaxed = true))
    }

    private fun pages(preferences: ReaderPreferences, viewer: Viewer?): ReaderSettingsPages {
        val viewModel = ReaderSettingsViewModel(
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
        return ReaderSettingsPages.Manga(viewModel, filters, MutableStateFlow(viewer))
    }
}

class NovelSheetProbe : SheetProbe {
    override val tabCount = 5
    override val seriesModeRows = listOf("Reading mode", "Chapter display")
    override fun toString() = "novels"

    override fun pages(): ReaderSettingsPages = pages(
        NovelPreferences(EmittingPreferenceStore()),
        NovelPageLayout.SCROLL,
    )

    override fun volumeKeyPages(paged: Boolean): ReaderSettingsPages {
        val preferences = NovelPreferences(EmittingPreferenceStore())
        preferences.readerUseVolumeButtons().set(true)
        return pages(preferences, if (paged) NovelPageLayout.PAGED_LTR else NovelPageLayout.SCROLL)
    }

    private fun pages(preferences: NovelPreferences, layout: NovelPageLayout): ReaderSettingsPages {
        val filters = ReaderDisplayFilters(
            customBrightness = preferences.readerCustomBrightness(),
            customBrightnessValue = preferences.readerCustomBrightnessValue(),
            colorFilter = preferences.readerColorFilter(),
            colorFilterValue = preferences.readerColorFilterValue(),
            colorFilterMode = preferences.readerColorFilterMode(),
            grayscale = preferences.readerGrayscale(),
            invertedColors = preferences.readerInvertedColors(),
            contrastPercent = preferences.readerContrast(),
        )
        return ReaderSettingsPages.Novel(
            preferences = preferences,
            textSettings = FixedTextSettings(layout),
            filters = filters,
            orientation = flowOf(0),
            onChangeOrientation = {},
            installedFonts = { emptyList() },
        )
    }
}

private class FixedTextSettings(layout: NovelPageLayout) : ReaderTextSettings {
    override val state = MutableStateFlow(
        ReaderTextState(fontSize = 18, followSystemTheme = true, backgroundColor = "#000000", textColor = "#FFFFFF"),
    )
    override fun setFontSize(size: Int) = Unit
    override fun followSystemTheme() = Unit
    override fun setThemeColors(background: String, textColor: String) = Unit

    override val renderingMode: Flow<NovelRenderingMode?> = flowOf(null)

    override fun setRenderingMode(mode: NovelRenderingMode?) = Unit

    override val pageLayout: Flow<NovelPageLayout?> = flowOf(null)

    override val effectivePageLayout = MutableStateFlow(layout)

    override fun setPageLayout(layout: NovelPageLayout?) = Unit
}
