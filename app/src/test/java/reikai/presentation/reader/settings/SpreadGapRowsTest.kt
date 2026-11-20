package reikai.presentation.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import eu.kanade.presentation.more.settings.screen.SettingsNovelReaderScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.DualPageView
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRenderingMode
import reikai.presentation.ComposeTestHost
import reikai.presentation.reader.ReaderDisplayFilters
import reikai.presentation.reader.ReaderTextSettings
import reikai.presentation.reader.ReaderTextState
import reikai.presentation.recents.EmittingPreferenceStore

/** Space between pages shows only while the reader can show two pages, and Select text only while it cannot: both
 *  Settings screens and the novel sheet. The manga sheet's rows are pinned in MangaReaderSheetRowsTest. */
@RunWith(RobolectricTestRunner::class)
class SpreadGapRowsTest {

    @get:Rule
    val compose = createComposeRule()

    /** The titles of the rows [rows] shows. */
    private fun shown(rows: @Composable () -> List<Pair<String, Boolean>>): List<String> {
        var titles = emptyList<String>()
        compose.setContent {
            ComposeTestHost { Rows { titles = rows().filter { it.second }.map { it.first } }.Content() }
        }
        compose.waitForIdle()
        return titles
    }

    private class Rows(val read: @Composable () -> Unit) {
        @Composable
        fun Content() = read()
    }

    private fun mangaSettings(dualPageView: DualPageView): List<String> {
        val preferences = ReaderPreferences(EmittingPreferenceStore())
        return shown {
            webGpuViewerGroup(preferences, MangaReaderSettingsRows(true, dualPageView)).preferenceItems
                .map { it.title to it.visible }
        }
    }

    private fun novelPreferences(
        dualPageView: DualPageView,
        layout: NovelPageLayout = NovelPageLayout.PAGED_LTR,
    ) = NovelPreferences(EmittingPreferenceStore()).apply {
        readerPageLayout().set(layout)
        readerDualPageView().set(dualPageView)
    }

    private fun novelSettings(
        dualPageView: DualPageView,
        layout: NovelPageLayout = NovelPageLayout.PAGED_LTR,
    ): List<String> {
        val preferences = novelPreferences(dualPageView, layout)
        return shown {
            SettingsNovelReaderScreen.getReadingGroup(preferences).preferenceItems.map { it.title to it.visible }
        }
    }

    private fun novelSheetShowsRow(
        dualPageView: DualPageView,
        row: String = SPACE_BETWEEN_PAGES,
        tab: String? = null,
    ): Boolean {
        val preferences = novelPreferences(dualPageView)
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
        val pages = ReaderSettingsPages.Novel(
            preferences = preferences,
            textSettings = FixedText,
            filters = filters,
            orientation = flowOf(0),
            onChangeOrientation = {},
            installedFonts = { emptyList() },
        )
        compose.setContent {
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
        compose.waitForIdle()
        tab?.let { compose.onNodeWithContentDescription(it).performClick() }
        return compose.onAllNodes(hasText(row)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `manga settings offer the space between pages with dual page view on`() {
        mangaSettings(DualPageView.ALWAYS) shouldContain SPACE_BETWEEN_PAGES
    }

    @Test
    fun `manga settings hide the space between pages with dual page view off`() {
        mangaSettings(DualPageView.NEVER) shouldNotContain SPACE_BETWEEN_PAGES
    }

    @Test
    fun `novel settings offer the space between pages with dual page view on`() {
        novelSettings(DualPageView.ALWAYS) shouldContain SPACE_BETWEEN_PAGES
    }

    @Test
    fun `novel settings hide the space between pages with dual page view off`() {
        novelSettings(DualPageView.NEVER) shouldNotContain SPACE_BETWEEN_PAGES
    }

    @Test
    fun `novel settings hide the space between pages while the novel scrolls`() {
        novelSettings(DualPageView.ALWAYS, NovelPageLayout.SCROLL) shouldNotContain SPACE_BETWEEN_PAGES
    }

    @Test
    fun `the novel reader sheet offers the space between pages with dual page view on`() {
        novelSheetShowsRow(DualPageView.ALWAYS) shouldBe true
    }

    @Test
    fun `the novel reader sheet hides the space between pages with dual page view off`() {
        novelSheetShowsRow(DualPageView.NEVER) shouldBe false
    }

    @Test
    fun `the novel reader sheet hides text selection while two pages show`() {
        novelSheetShowsRow(DualPageView.ALWAYS, SELECT_TEXT, tab = "Controls") shouldBe false
    }

    @Test
    fun `the novel reader sheet offers text selection on a single page`() {
        novelSheetShowsRow(DualPageView.NEVER, SELECT_TEXT, tab = "Controls") shouldBe true
    }

    @Test
    fun `novel settings hide text selection while always showing two pages`() {
        novelSettings(DualPageView.ALWAYS) shouldNotContain SELECT_TEXT
    }

    /** With no screen to ask, When wide may still show one page, where selection works. */
    @Test
    fun `novel settings offer text selection when two pages show only on a wide screen`() {
        novelSettings(DualPageView.WIDE) shouldContain SELECT_TEXT
    }

    private object FixedText : ReaderTextSettings {
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
        override val renderingMode: Flow<NovelRenderingMode?> = flowOf(null)
        override fun setRenderingMode(mode: NovelRenderingMode?) = Unit
        override val pageLayout: Flow<NovelPageLayout?> = flowOf(NovelPageLayout.PAGED_LTR)
        override val effectivePageLayout = MutableStateFlow(NovelPageLayout.PAGED_LTR)
        override fun setPageLayout(layout: NovelPageLayout?) = Unit
    }

    private companion object {
        const val SPACE_BETWEEN_PAGES = "Space between pages"
        const val SELECT_TEXT = "Select text by long press"
    }
}
