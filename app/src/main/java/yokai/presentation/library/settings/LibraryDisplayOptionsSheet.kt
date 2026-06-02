package yokai.presentation.library.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsLibraryController
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelPreferences
import yokai.i18n.MR
import yokai.presentation.library.settings.tabs.BadgesTab
import yokai.presentation.library.settings.tabs.CategoriesTab
import yokai.presentation.library.settings.tabs.DisplayTab
import yokai.presentation.library.settings.tabs.FilterTab
import yokai.presentation.library.settings.tabs.GroupTab
import yokai.presentation.library.settings.tabs.rememberRoutedPref

/**
 * Compose-side replacement for the legacy FilterBottomSheet and TabbedLibraryDisplaySheet.
 * One sheet, four tabs: Filter / Display / Badges / Categories. The legacy bottom sheets stay
 * alive untouched and remain wired into [eu.kanade.tachiyomi.ui.library.LibraryController]; the
 * Compose path uses this sheet exclusively.
 *
 * Sheet shape: no drag handle (the title + tab row carry the affordance), title and Reorder
 * action share one row, tab row blends into the sheet surface so the tabs feel like part of the
 * header rather than a separate band.
 */

const val TAB_FILTER = 0
const val TAB_DISPLAY = 1
const val TAB_GROUP = 2
const val TAB_BADGES = 3
const val TAB_CATEGORIES = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDisplayOptionsSheet(
    initialTab: Int,
    onDismiss: () -> Unit,
    detectedMangaTypes: Set<Int> = emptySet(),
    loggedTrackerNames: List<String> = emptyList(),
    /**
     * Which library tab the sheet was opened from. Tab composables route shareable visual prefs
     * via this flag combined with [yokai.domain.base.BasePreferences.useSharedLibraryDisplayPrefs];
     * tab-aware management actions (Edit categories, hidden manga-only toggles) read this directly.
     */
    isNovelTab: Boolean = false,
) {
    // Faithful port of the legacy TabbedLibraryDisplaySheet sizing: opens at the equivalent of
    // STATE_EXPANDED (skip the M3 PartiallyExpanded state entirely) so the user sees the full
    // tab content immediately rather than the M3 default ~30-40% partial peek. We still cap
    // the content height to half-screen below so the sheet does not balloon to 95% on tablets.
    // Drag-down naturally dismisses; tap-outside dismisses via the scrim. No confirmValueChange
    // veto — that would block the scrim's Hidden transition and break tap-outside.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(initialPage = initialTab) { 5 }
    val scope = rememberCoroutineScope()
    val preferences: PreferencesHelper = Injekt.get()
    val novelPrefs: NovelPreferences = Injekt.get()
    // filterOrder lives alongside the per-library filter values, so route by tab regardless of
    // the shared display-prefs toggle (which is for truly visual settings — grid size, badges).
    val filterOrderPref = rememberRoutedPref(isNovelTab, preferences.filterOrder(), novelPrefs.filterOrder())
    val filterOrder by filterOrderPref.collectAsState()
    val router = LocalRouter.currentOrThrow
    // Cap generously so the sheet grows with its content (like Komikku) instead of the cramped
    // half-screen it used before; content beyond the cap scrolls inside its tab.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    // containerColor reads ?attr/background straight off the theme (createMdc3Theme doesn't
    // surface the legacy Reikai attr as an M3 token), keeping the sheet flush with the library.
    val context = LocalContext.current
    val sheetContainerColor = remember(context) {
        Color(context.getResourceColor(eu.kanade.tachiyomi.R.attr.background))
    }
    // Komikku's adaptive sheet: a centered floating dialog on wide screens (tablet / unfolded
    // foldable), a bottom sheet on phones.
    val isTabletUi = LocalConfiguration.current.screenWidthDp >= 600

    val sheetBody: @Composable () -> Unit = {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.strings.display_options),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        router.pushController(SettingsLibraryController().withFadeTransaction())
                        onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(MR.strings.more_library_settings),
                    )
                }
            }
            // Tabs drive the pager (and swiping the pager updates the tab indicator). Transparent
            // container + thin secondary indicator keeps the strip flush with the sheet surface;
            // the settings gear shares the row as a corner action.
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 12.dp,
                containerColor = Color.Transparent,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                divider = {},
            ) {
                TabLabel(MR.strings.filter, pagerState.currentPage == TAB_FILTER) {
                    scope.launch { pagerState.animateScrollToPage(TAB_FILTER) }
                }
                TabLabel(MR.strings.display, pagerState.currentPage == TAB_DISPLAY) {
                    scope.launch { pagerState.animateScrollToPage(TAB_DISPLAY) }
                }
                TabLabel(MR.strings.group, pagerState.currentPage == TAB_GROUP) {
                    scope.launch { pagerState.animateScrollToPage(TAB_GROUP) }
                }
                TabLabel(MR.strings.badges, pagerState.currentPage == TAB_BADGES) {
                    scope.launch { pagerState.animateScrollToPage(TAB_BADGES) }
                }
                TabLabel(MR.strings.categories, pagerState.currentPage == TAB_CATEGORIES) {
                    scope.launch { pagerState.animateScrollToPage(TAB_CATEGORIES) }
                }
            }
            // Swipeable tab content. Each tab provides its own verticalScroll and is capped to a
            // fraction of the screen. The sheet height eases between tabs (~250ms) rather than
            // snapping (an instant jump was jarring) WITHOUT animating the pager's own measured
            // size: that re-measured the pager under the drag and intermittently wedged the swipe.
            // Instead the pager measures each page at its natural (capped) height via
            // wrapContentHeight(unbounded) and reports it through onSizeChanged; only the outer clip
            // box animates to the current page's height. The pager's measurement never depends on
            // the animation, so the gesture stays stable.
            val density = LocalDensity.current
            val pageHeights = remember { mutableStateMapOf<Int, Int>() }
            val targetHeightPx = pageHeights[pagerState.currentPage] ?: 0
            val heightAnim = remember { Animatable(0f) }
            var heightInitialized by remember { mutableStateOf(false) }
            LaunchedEffect(targetHeightPx) {
                if (targetHeightPx <= 0) return@LaunchedEffect
                if (!heightInitialized) {
                    // Snap the first known height so the sheet doesn't grow from zero on open.
                    heightAnim.snapTo(targetHeightPx.toFloat())
                    heightInitialized = true
                } else {
                    heightAnim.animateTo(targetHeightPx.toFloat(), animationSpec = tween(250))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (heightInitialized) {
                            Modifier
                                .height(with(density) { heightAnim.value.toDp() })
                                .clipToBounds()
                        } else {
                            Modifier
                        },
                    ),
            ) {
                HorizontalPager(
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                    // unbounded height so each page measures its natural (heightIn-capped) size
                    // independent of the animated clip box above; that keeps onSizeChanged honest
                    // and keeps the pager's own measurement out of the height animation.
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxSheetHeight)
                            .onSizeChanged { pageHeights[page] = it.height }
                            .padding(horizontal = 16.dp),
                    ) {
                        when (page) {
                            TAB_FILTER -> FilterTab(
                                detectedMangaTypes = detectedMangaTypes,
                                loggedTrackerNames = loggedTrackerNames,
                                filterOrder = filterOrder,
                                onFilterOrderChanged = { filterOrderPref.set(it) },
                                onApply = onDismiss,
                                isNovelTab = isNovelTab,
                            )
                            TAB_DISPLAY -> DisplayTab(isNovelTab = isNovelTab)
                            TAB_GROUP -> GroupTab(isNovelTab = isNovelTab)
                            TAB_BADGES -> BadgesTab(isNovelTab = isNovelTab)
                            TAB_CATEGORIES -> CategoriesTab(onDismissSheet = onDismiss, isNovelTab = isNovelTab)
                        }
                    }
                }
            }
        }
    }

    if (isTabletUi) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // Full-screen centered Box keeps the Dialog window stable while only the Surface
            // animates (mirrors Komikku's AdaptiveSheet), so the expand/shrink between tabs is
            // smooth instead of the window re-layouting per frame. Tap outside the Surface
            // dismisses; taps on the Surface are absorbed.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 460.dp)
                        .clickable(interactionSource = null, indication = null, onClick = {}),
                    shape = RoundedCornerShape(28.dp),
                    color = sheetContainerColor,
                    shadowElevation = 6.dp,
                    tonalElevation = 0.dp,
                ) {
                    sheetBody()
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            containerColor = sheetContainerColor,
        ) {
            sheetBody()
        }
    }
}

@Composable
private fun TabLabel(
    stringRes: dev.icerock.moko.resources.StringResource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(stringResource(stringRes), style = MaterialTheme.typography.labelLarge) },
    )
}
