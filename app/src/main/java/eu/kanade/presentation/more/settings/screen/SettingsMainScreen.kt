package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.navigation3.runtime.NavKey
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.about.getVersionName
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.Navigator
import mihon.core.navigation.SettingsSearchRoute
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.Code
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Security
import mihon.icons.materialsymbols.rounded.Storage
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.rounded.Verified
import mihon.presentation.core.util.isTabletUi
import reikai.presentation.components.LocalShownContent
import reikai.presentation.recommendation.SettingsRecommendationsScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Serializable
data object SettingsMainScreen : NavKey {

    @Composable
    private fun getPalerSurface(): Color {
        val surface = MaterialTheme.colorScheme.surface
        val dark = isSystemInDarkTheme()
        return remember(surface, dark) {
            val arr = FloatArray(3)
            ColorUtils.colorToHSL(surface.toArgb(), arr)
            arr[2] = if (dark) {
                arr[2] - 0.05f
            } else {
                arr[2] + 0.02f
            }.coerceIn(0f, 1f)
            Color.hsl(arr[0], arr[1], arr[2])
        }
    }

    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val twoPane = isTabletUi()
        val containerColor = if (twoPane) getPalerSurface() else MaterialTheme.colorScheme.surface
        val topBarState = rememberTopAppBarState()
        // RK: a reader screen leaves with its content type
        val shownContent = LocalShownContent.current
        val items = items.filter { shownContent.showsScreen(it.screen) }

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_settings),
                    navigateUp = {
                        navigator.popTo { it == SettingsMainScreen }
                        navigator.pop()
                    },
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_search),
                                    icon = MaterialSymbols.Rounded.Search,
                                    onClick = { navigator.navigate(SettingsSearchRoute, twoPane) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = containerColor,
            content = { contentPadding ->
                val state = rememberLazyListState()
                val indexSelected = if (twoPane) {
                    val detail = navigator.routes.takeLastWhile { it != SettingsMainScreen }.firstOrNull()
                    items.indexOfFirst { it.screen == detail }
                        .takeIf { it > -1 } // Don't trigger if route for right side hasn't been added yet
                        ?.also {
                            LaunchedEffect(Unit) {
                                state.animateScrollToItem(it)
                                if (it > 0) {
                                    // Lift scroll
                                    topBarState.contentOffset = topBarState.heightOffsetLimit
                                }
                            }
                        }
                } else {
                    null
                }

                LazyColumn(
                    state = state,
                    contentPadding = contentPadding,
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.hashCode() },
                    ) { index, item ->
                        val selected = indexSelected == index
                        var modifier: Modifier = Modifier
                        var contentColor = LocalContentColor.current
                        if (twoPane) {
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .then(
                                    if (selected) {
                                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                    } else {
                                        Modifier
                                    },
                                )
                            if (selected) {
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        }
                        CompositionLocalProvider(LocalContentColor provides contentColor) {
                            TextPreferenceWidget(
                                modifier = modifier,
                                title = stringResource(item.titleRes),
                                subtitle = item.formatSubtitle(),
                                icon = item.icon,
                                onPreferenceClick = { navigator.navigate(item.screen, twoPane) },
                            )
                        }
                    }
                }
            },
        )
    }

    private fun Navigator.navigate(screen: NavKey, twoPane: Boolean) {
        if (twoPane) popTo { it == SettingsMainScreen }
        push(screen)
    }

    private data class Item(
        val titleRes: StringResource,
        val subtitleRes: StringResource? = null,
        val formatSubtitle: @Composable () -> String? = { subtitleRes?.let { stringResource(it) } },
        val icon: ImageVector,
        val screen: NavKey,
    )

    private val items = listOf(
        Item(
            titleRes = MR.strings.pref_category_appearance,
            subtitleRes = MR.strings.pref_appearance_summary,
            icon = MaterialSymbols.Rounded.Palette,
            screen = SettingsAppearanceScreen,
        ),
        Item(
            titleRes = MR.strings.pref_category_library,
            subtitleRes = MR.strings.pref_library_summary,
            icon = MaterialSymbols.Rounded.CollectionsBookmark,
            screen = SettingsLibraryScreen,
        ),
        // RK --> one reader entry per content type, replacing the single Reader screen. Each is
        // self-contained: every setting on it applies to that reader only, so neither screen has to
        // explain a value shared with the other one.
        Item(
            titleRes = MR.strings.pref_category_manga_reader,
            subtitleRes = MR.strings.pref_category_manga_reader_summary,
            icon = MaterialSymbols.AutoMirroredRounded.ChromeReaderMode,
            screen = SettingsMangaReaderScreen,
        ),
        Item(
            titleRes = MR.strings.pref_category_novel_reader,
            subtitleRes = MR.strings.pref_category_novel_reader_summary,
            icon = MaterialSymbols.AutoMirroredRounded.ChromeReaderMode,
            screen = SettingsNovelReaderScreen,
        ),
        // RK <--
        Item(
            titleRes = MR.strings.pref_category_downloads,
            subtitleRes = MR.strings.pref_downloads_summary,
            icon = MaterialSymbols.Rounded.Download,
            screen = SettingsDownloadScreen,
        ),
        Item(
            titleRes = MR.strings.pref_category_tracking,
            subtitleRes = MR.strings.pref_tracking_summary,
            icon = MaterialSymbols.Rounded.Sync,
            screen = SettingsTrackingScreen,
        ),
        Item(
            titleRes = MR.strings.pref_category_browse, // RK: "Browse and sources", which holds source settings
            subtitleRes = MR.strings.pref_browse_summary,
            icon = MaterialSymbols.Rounded.Explore,
            screen = SettingsBrowseScreen,
        ),
        // RK: recommendations, a top-level entry of its own rather than a page inside Library.
        Item(
            titleRes = MR.strings.pref_recommendations,
            subtitleRes = MR.strings.pref_recommendations_summary,
            icon = MaterialSymbols.Rounded.Verified,
            screen = SettingsRecommendationsScreen,
        ),
        Item(
            titleRes = MR.strings.label_data_storage,
            subtitleRes = MR.strings.pref_backup_summary,
            icon = MaterialSymbols.Rounded.Storage,
            screen = SettingsDataScreen,
        ),
        Item(
            titleRes = MR.strings.pref_category_security,
            subtitleRes = MR.strings.pref_security_summary,
            icon = MaterialSymbols.Rounded.Security,
            screen = SettingsSecurityScreen,
        ),
        // RK: E-Hentai and MangaDex are reached from Browse and sources now, in its Source settings
        // group, so every source's settings live in one place rather than two.
        Item(
            titleRes = MR.strings.pref_category_advanced,
            subtitleRes = MR.strings.pref_advanced_summary,
            icon = MaterialSymbols.Rounded.Code,
            screen = SettingsAdvancedScreen,
        ),
        Item(
            titleRes = MR.strings.pref_category_about,
            formatSubtitle = {
                "${stringResource(MR.strings.app_name)} ${getVersionName(buildTime = null)}"
            },
            icon = MaterialSymbols.Rounded.Info,
            screen = AboutScreen, // RK: the key settings search opens, so two panes mark it
        ),
    )
}

/**
 * Opens settings at [screen], or at the main screen if null. In two panes, reuses open settings as the list pane and
 * shows Appearance when no [screen] is given.
 */
fun Navigator.openSettings(screen: NavKey? = null, twoPane: Boolean) {
    if (!twoPane) {
        push(screen ?: SettingsMainScreen)
        return
    }
    if (!popTo { it == SettingsMainScreen }) push(SettingsMainScreen)
    push(screen ?: SettingsAppearanceScreen)
}
