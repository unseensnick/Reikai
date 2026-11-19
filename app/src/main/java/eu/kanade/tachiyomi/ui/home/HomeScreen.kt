package eu.kanade.tachiyomi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.ResultEventBus
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.browse.BrowseShowExtensionsEvent
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibrarySearchEvent
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.stats.StatsShortcut
import reikai.presentation.details.detailsScreen
import reikai.presentation.recents.RecentsMode
import reikai.presentation.recents.RecentsModeEvent
import reikai.presentation.recents.RecentsTab
import reikai.presentation.recents.ShowsUpdatesBadge
import reikai.presentation.stats.StatsTab
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.util.collectAsState
import eu.kanade.presentation.util.Tab as NavTab

object HomeScreen : Screen() {

    // RK: the search, tab and bottom nav channels are TabEvent, LibrarySearchEvent and
    //     ShowBottomNavEvent on the result bus, upstream's events.

    @Suppress("ConstPropertyName")
    private const val TabFadeDuration = 200

    @Suppress("ConstPropertyName")
    private const val TabNavigatorKey = "HomeTabs"

    // RK --> the tab set is a value now, not a constant: one setting replaces Updates and History
    // with a single Recents tab holding both, another adds a Statistics tab. Both off by default, so
    // this is the upstream list until someone asks for otherwise. `NavTab` is the navigable kind.
    private fun tabs(combinedRecents: Boolean, statsTab: Boolean): List<NavTab> = listOfNotNull(
        LibraryTab,
        RecentsTab.takeIf { combinedRecents },
        UpdatesTab.takeUnless { combinedRecents },
        HistoryTab.takeUnless { combinedRecents },
        BrowseTab,
        StatsTab.takeIf { statsTab },
        MoreTab,
    )

    /**
     * The tab [event] opens, sending on what it asks of that tab. A recents intent tells the combined
     * tab which mode it meant, and only when that tab is in the set, since nothing else reads it.
     */
    internal fun tabFor(event: TabEvent, combinedRecents: Boolean, bus: ResultEventBus): NavTab = when (event) {
        is TabEvent.Library -> {
            // The query carries its content type, so it lands on that series' own chip.
            if (event.query != null) bus.sendResult(LibrarySearchEvent(event.query, event.contentType))
            LibraryTab
        }
        TabEvent.Updates -> recentsTarget(combinedRecents, RecentsMode.UPDATES, bus)
        TabEvent.History -> recentsTarget(combinedRecents, RecentsMode.HISTORY, bus)
        is TabEvent.Browse -> {
            if (event.toExtensions) bus.sendResult(BrowseShowExtensionsEvent)
            BrowseTab
        }
        is TabEvent.More -> MoreTab
    }

    private fun recentsTarget(combinedRecents: Boolean, mode: RecentsMode, bus: ResultEventBus): NavTab = when {
        combinedRecents -> RecentsTab.also { bus.sendResult(RecentsModeEvent(mode)) }
        mode == RecentsMode.HISTORY -> HistoryTab
        else -> UpdatesTab
    }

    /** Where a tab that just left the set sends the user, so nothing is left rendering unselected. */
    private fun replacementFor(
        tab: cafe.adriel.voyager.navigator.tab.Tab,
        combinedRecents: Boolean,
        statsTab: Boolean,
    ): NavTab? = when {
        combinedRecents && (tab is UpdatesTab || tab is HistoryTab) -> RecentsTab
        !combinedRecents && tab is RecentsTab -> UpdatesTab
        // The setting is reached through More, so that is where the user came from.
        !statsTab && tab is StatsTab -> MoreTab
        else -> null
    }
    // RK <--

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val resultEventBus = LocalResultEventBus.current // RK
        // RK: read here rather than per bar, so both bars and the fixup below agree within a frame.
        val combinedRecents by remember { context.appGraph.uiPreferences.combinedRecentsTab }.collectAsState()
        val statsTab = remember { context.appGraph.uiPreferences.statsShortcut }.collectAsState().value ==
            StatsShortcut.TAB
        val tabs = tabs(combinedRecents, statsTab)
        TabNavigator(
            tab = LibraryTab,
            key = TabNavigatorKey,
        ) { tabNavigator ->
            // RK: a tab dropped from the list keeps rendering with nothing selected, since selection
            // is a per-item class comparison rather than a lookup, so it is moved on deliberately.
            LaunchedEffect(combinedRecents, statsTab) {
                replacementFor(tabNavigator.current, combinedRecents, statsTab)?.let { tabNavigator.current = it }
            }
            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                val tabletUi = isTabletUi()
                val navigationSuiteType = if (tabletUi) {
                    NavigationSuiteType.NavigationRail
                } else {
                    NavigationSuiteType.NavigationBar
                }
                val navigationSuiteState = rememberNavigationSuiteScaffoldState()
                LaunchedEffect(navigationSuiteState, tabletUi) {
                    if (tabletUi) navigationSuiteState.show()
                }
                // RK -->
                ResultEffect<ShowBottomNavEvent> {
                    if (tabletUi || it.visible) {
                        navigationSuiteState.show()
                    } else {
                        navigationSuiteState.hide()
                    }
                }
                // A tab a shortcut switches to shows the bar, which a selection left on the last tab hid. A tab that
                // shows in selection mode hides it again as it opens.
                LaunchedEffect(tabNavigator.current) { navigationSuiteState.show() }
                // RK <--

                NavigationSuiteScaffold(
                    navigationSuiteType = navigationSuiteType,
                    state = navigationSuiteState,
                    navigationSuiteColors = NavigationSuiteDefaults.colors(
                        navigationRailContainerColor = MaterialTheme.colorScheme
                            .surfaceColorAtElevation(3.dp),
                    ),
                    navigationItemVerticalArrangement = Arrangement.Center,
                    navigationItems = {
                        tabs.fastForEach { NavigationSuiteItem(it, navigationSuiteType) } // RK
                    },
                ) {
                    AnimatedContent(
                        targetState = tabNavigator.current,
                        transitionSpec = {
                            materialFadeThroughIn(
                                initialScale = 1f,
                                durationMillis = TabFadeDuration,
                            ) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)
                        },
                        label = "tabContent",
                    ) {
                        tabNavigator.saveableState(key = "currentTab", it) {
                            it.Content()
                        }
                    }
                }
            }

            val goToLibraryTab = { tabNavigator.current = LibraryTab }

            BackHandler(enabled = tabNavigator.current != LibraryTab, onBack = goToLibraryTab)

            // RK -->
            ResultEffect<TabEvent> {
                tabNavigator.current = tabFor(it, combinedRecents, resultEventBus)

                if (it is TabEvent.Library && it.entryToOpen != null) {
                    navigator.push(it.entryToOpen.detailsScreen())
                }
                if (it is TabEvent.More && it.toDownloads) {
                    navigator.push(DownloadQueueScreen)
                }
            }
            // RK <--
        }
    }

    @Composable
    private fun NavigationSuiteItem(
        tab: eu.kanade.presentation.util.Tab,
        navigationSuiteType: NavigationSuiteType,
    ) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        // RK: read once, since each read of the getter builds the tab's icon painter again
        val options = tab.options
        NavigationSuiteItem(
            navigationSuiteType = navigationSuiteType,
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = {
                Icon(
                    painter = options.icon!!, // RK
                    contentDescription = options.title, // RK
                )
            },
            label = {
                Text(
                    text = options.title, // RK
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            badge = tabBadge(tab),
        )
    }

    @Composable
    private fun tabBadge(tab: eu.kanade.presentation.util.Tab): (@Composable () -> Unit)? {
        val context = LocalContext.current
        val count by produceState(initialValue = 0, tab) {
            val graph = context.appGraph
            when (tab) {
                // RK: asks what the tab does rather than which class it is, since the Updates
                // feed can now be drawn by the combined tab instead.
                is ShowsUpdatesBadge -> {
                    combine(
                        graph.libraryPreferences.newShowUpdatesCount.changes(),
                        graph.libraryPreferences.newUpdatesCount.changes(),
                    ) { show, count ->
                        if (show) count else 0
                    }
                        .collectLatest { value = it }
                }

                is BrowseTab -> {
                    // RK: every extension kind, as the Extensions tab's own badge counts
                    graph.extensionUpdateCounts.total
                        .collectLatest { value = it }
                }

                else -> value = 0
            }
        }
        if (count <= 0) return null
        return {
            Badge {
                val desc = when (tab) {
                    // RK: same capability check as the count above, so the two cannot disagree.
                    is ShowsUpdatesBadge -> pluralStringResource(
                        MR.plurals.notification_chapters_generic,
                        count = count,
                        count,
                    )

                    is BrowseTab -> pluralStringResource(
                        MR.plurals.update_check_notification_ext_updates,
                        count = count,
                        count,
                    )

                    else -> null
                }
                Text(
                    text = count.toString(),
                    modifier = Modifier.semantics {
                        if (desc != null) contentDescription = desc
                    },
                )
            }
        }
    }

    // RK: search, openTab and showBottomNav are TabEvent and ShowBottomNavEvent below, on the result bus
}

// RK --> upstream's events, which reach Home over the result bus in place of HomeScreen's channels
sealed interface TabEvent {
    /** Opens [entryToOpen] of either type over the library, or searches it for [query] on [contentType]'s chip. */
    data class Library(
        val entryToOpen: EntryId? = null,
        val query: String? = null,
        val contentType: ContentType = ContentType.ALL,
    ) : TabEvent
    data object Updates : TabEvent
    data object History : TabEvent
    data class Browse(val toExtensions: Boolean = false) : TabEvent
    data class More(val toDownloads: Boolean) : TabEvent
}

data class ShowBottomNavEvent(val visible: Boolean)
// RK <--
