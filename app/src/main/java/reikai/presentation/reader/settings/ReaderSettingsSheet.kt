package reikai.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Palette
import reikai.domain.novel.NovelPreferences
import reikai.novel.font.NovelFont
import reikai.presentation.icons.Contrast
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.icons.TouchApp
import reikai.presentation.reader.ReaderDisplayFilters
import reikai.presentation.reader.ReaderTextSettings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * What the in-reader settings sheet edits, answered per content type so the compiler makes both
 * readers fill every tab. The filters are the session's own set, shared by both page sets.
 */
sealed interface ReaderSettingsPages {
    val filters: ReaderDisplayFilters

    data class Manga(
        val viewModel: ReaderSettingsViewModel,
        override val filters: ReaderDisplayFilters,
    ) : ReaderSettingsPages

    data class Novel(
        val preferences: NovelPreferences,
        val textSettings: ReaderTextSettings,
        override val filters: ReaderDisplayFilters,
        /** The fonts the user added, listed off the main thread when the font picker opens. */
        val installedFonts: suspend () -> List<NovelFont>,
    ) : ReaderSettingsPages
}

/** The same four tabs for both readers, so the sheet reads the same whichever is open. */
private enum class ReaderSettingsTab(val titleRes: StringResource, val icon: ImageVector) {
    Reading(MR.strings.pref_category_reading, MaterialSymbols.Rounded.LocalLibrary),
    Appearance(MR.strings.pref_category_appearance, MaterialSymbols.Rounded.Palette),
    Controls(MR.strings.reader_settings_controls, ReikaiIcons.TouchApp),
    Filters(MR.strings.reader_settings_filters, ReikaiIcons.Contrast),
}

/**
 * The reader's settings sheet, opened from the bar's gear. The Filters tab drops the dim and the menus
 * so the page it tints stays in view.
 */
@Composable
fun ReaderSettingsSheet(
    pages: ReaderSettingsPages,
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
) {
    val tabs = ReaderSettingsTab.entries
    val tabTitles = tabs.map { stringResource(it.titleRes) }
    val pagerState = rememberPagerState { tabs.size }

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = {
                onDismissRequest()
                onShowMenus()
            },
            tabTitles = tabTitles,
            pagerState = pagerState,
            tabIcons = tabs.map { it.icon },
        ) { page ->
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window

            LaunchedEffect(pagerState.currentPage) {
                if (tabs[pagerState.currentPage] == ReaderSettingsTab.Filters) {
                    window?.setDimAmount(0f)
                    onHideMenus()
                } else {
                    window?.setDimAmount(0.5f)
                    onShowMenus()
                }
            }

            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tabs[page]) {
                    ReaderSettingsTab.Reading -> when (pages) {
                        is ReaderSettingsPages.Manga -> MangaReadingPage(pages.viewModel)
                        is ReaderSettingsPages.Novel -> NovelReadingPage(pages)
                    }
                    ReaderSettingsTab.Appearance -> when (pages) {
                        is ReaderSettingsPages.Manga -> MangaAppearancePage(pages.viewModel)
                        is ReaderSettingsPages.Novel -> NovelAppearancePage(pages)
                    }
                    ReaderSettingsTab.Controls -> when (pages) {
                        is ReaderSettingsPages.Manga -> MangaControlsPage(pages.viewModel)
                        is ReaderSettingsPages.Novel -> NovelControlsPage(pages.preferences)
                    }
                    ReaderSettingsTab.Filters -> ReaderFiltersPage(pages.filters)
                }
            }
        }
    }
}
