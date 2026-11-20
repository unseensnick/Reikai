package eu.kanade.tachiyomi.ui.home

import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.ResultEventBus
import eu.kanade.presentation.util.rememberAnimatedVectorPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseShowExtensionsEvent
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibrarySearchEvent
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import mihon.app.di.appGraph
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.Navigator
import mihon.core.navigation.NavigatorDisplay
import mihon.core.navigation.rememberNavigator
import mihon.presentation.core.util.isTabletUi
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.stats.StatsShortcut
import reikai.navigation.rememberTabViewModelStoresDecorator
import reikai.presentation.components.cachedAnimatedVector
import reikai.presentation.details.detailsRoute
import reikai.presentation.recents.RecentsMode
import reikai.presentation.recents.RecentsModeEvent
import reikai.presentation.recents.RecentsTab
import reikai.presentation.recents.ShowsUpdatesBadge
import reikai.presentation.stats.StatsTab
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
sealed interface TopLevelRoute : NavKey {

    val reselectEventKey: String
        get() = "TabReselectEvent:$this"

    @Composable
    fun options(isSelected: Boolean): TabOptions

    @Serializable
    data object Library : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = cachedAnimatedVector(R.drawable.animated_library) // RK
            return TabOptions(
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object Updates : TopLevelRoute, ShowsUpdatesBadge { // RK: the badge marker
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = cachedAnimatedVector(R.drawable.animated_updates) // RK
            return TabOptions(
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object History : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = cachedAnimatedVector(R.drawable.animated_history) // RK
            return TabOptions(
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object Browse : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val enter = cachedAnimatedVector(R.drawable.animated_browse_enter) // RK
            val exit = cachedAnimatedVector(R.drawable.animated_browse_exit) // RK
            return TabOptions(
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(enter, exit, isSelected),
            )
        }
    }

    @Serializable
    data object More : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val enter = cachedAnimatedVector(R.drawable.animated_more_enter) // RK
            val exit = cachedAnimatedVector(R.drawable.animated_more_exit) // RK
            return TabOptions(
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(enter, exit, isSelected),
            )
        }
    }
}

@Composable
fun HomeScreen() {
    val tabNavigator = rememberNavigator(TopLevelRoute.Library)
    val navigator = LocalNavigator.current
    val resultEventBus = LocalResultEventBus.current
    // RK --> the tab set is a value: one setting replaces Updates and History with a Recents tab, another adds
    //        Statistics. Read here rather than per bar, so the bar and the fixup below agree within a frame.
    val context = LocalContext.current
    val combinedRecents by remember { context.appGraph.uiPreferences.combinedRecentsTab }.collectAsState()
    val statsTab = remember { context.appGraph.uiPreferences.statsShortcut }.collectAsState().value ==
        StatsShortcut.TAB
    val tabs = tabs(combinedRecents, statsTab)
    // RK <--
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

    ResultEffect<ShowBottomNavEvent> {
        if (tabletUi || it.visible) {
            navigationSuiteState.show()
        } else {
            navigationSuiteState.hide()
        }
    }
    // RK: a tab a shortcut switches to shows the bar, which a selection left on the last tab hid. A tab that
    //     shows in selection mode hides it again as it opens.
    LaunchedEffect(tabNavigator.current) { navigationSuiteState.show() }

    NavigationSuiteScaffold(
        navigationSuiteType = navigationSuiteType,
        state = navigationSuiteState,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationRailContainerColor = MaterialTheme.colorScheme
                .surfaceColorAtElevation(3.dp),
        ),
        navigationItemVerticalArrangement = Arrangement.Center,
        navigationItems = {
            tabs.fastForEach { NavigationSuiteItem(it, tabNavigator, navigationSuiteType) } // RK
        },
    ) {
        // RK --> a tab that left the set (a restore, or the setting changed) is replaced before anything draws
        //        it, so no removed tab composes and builds models that would then outlive it
        val replacement = replacementFor(tabNavigator.current, combinedRecents, statsTab)
        if (replacement != null) {
            SideEffect { tabNavigator.replaceAll(replacement) }
            return@NavigationSuiteScaffold
        }
        // RK <--
        val tabStateHolder = rememberSaveableStateHolder()
        NavigatorDisplay(
            navigator = tabNavigator,
            entryDecorators = listOf(
                remember(tabStateHolder) {
                    NavEntryDecorator { entry ->
                        tabStateHolder.SaveableStateProvider(entry.contentKey) { entry.Content() }
                    }
                },
                // RK: each tab keeps its own models, dropped with the tab when it leaves the bar
                rememberTabViewModelStoresDecorator(droppedTabs(tabs)),
            ),
            transitionSpec = { spec },
            popTransitionSpec = { spec },
            predictivePopTransitionSpec = { spec },
            entryProvider = entryProvider {
                entry<TopLevelRoute.Library> {
                    LibraryTab()
                }

                entry<TopLevelRoute.Updates> {
                    UpdatesTab()
                }

                entry<TopLevelRoute.History> {
                    HistoryTab()
                }

                entry<TopLevelRoute.Browse> {
                    BrowseTab()
                }

                entry<TopLevelRoute.More> {
                    MoreTab()
                }

                // RK -->
                entry<RecentsTabRoute> {
                    RecentsTab()
                }

                entry<StatsTabRoute> {
                    StatsTab()
                }
                // RK <--
            },
        )
    }

    ResultEffect<TabEvent> {
        tabNavigator.replaceAll(tabFor(it, combinedRecents, resultEventBus)) // RK

        pageFor(it)?.let(navigator::push) // RK: the page a tab event opens over its tab
    }
}

@Composable
private fun NavigationSuiteItem(
    tab: TopLevelRoute,
    tabNavigator: Navigator,
    navigationSuiteType: NavigationSuiteType,
) {
    val resultEventBus = LocalResultEventBus.current
    val selected = tabNavigator.current == tab
    val options = tab.options(selected)

    NavigationSuiteItem(
        navigationSuiteType = navigationSuiteType,
        selected = selected,
        onClick = {
            if (!selected) {
                tabNavigator.replaceAll(tab)
            } else {
                resultEventBus.sendResult(
                    resultKey = tab.reselectEventKey,
                    result = Unit,
                )
            }
        },
        icon = {
            Icon(
                painter = options.icon,
                contentDescription = options.title,
            )
        },
        label = {
            Text(
                text = options.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        badge = tabBadge(tab),
    )
}

@Composable
private fun tabBadge(tab: TopLevelRoute): (@Composable () -> Unit)? {
    val context = LocalContext.current
    val count by produceState(initialValue = 0, tab) {
        val graph = context.appGraph
        when (tab) {
            // RK: asks what the tab does rather than which it is, since the combined tab draws Updates too
            is ShowsUpdatesBadge -> {
                combine(
                    graph.libraryPreferences.newShowUpdatesCount.changes(),
                    graph.libraryPreferences.newUpdatesCount.changes(),
                ) { show, count ->
                    if (show) count else 0
                }
                    .collectLatest { value = it }
            }

            is TopLevelRoute.Browse -> {
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
                is ShowsUpdatesBadge -> pluralStringResource( // RK: the count's own check
                    MR.plurals.notification_chapters_generic,
                    count = count,
                    count,
                )

                is TopLevelRoute.Browse -> pluralStringResource(
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

sealed interface TabEvent {
    // RK: opens [entryToOpen] of either type over the library, or searches it for [query] on [contentType]'s chip
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

@Suppress("ConstPropertyName")
private const val TabFadeDuration = 200

data class ShowBottomNavEvent(val visible: Boolean)

data class TabOptions(
    val title: String,
    val icon: Painter,
)

private val spec = materialFadeThroughIn(
    initialScale = 1f,
    durationMillis = TabFadeDuration,
) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)

// RK --> the tab set, and where a tab event or a tab that left the set lands
private fun tabs(combinedRecents: Boolean, statsTab: Boolean): List<TopLevelRoute> = listOfNotNull(
    TopLevelRoute.Library,
    RecentsTabRoute.takeIf { combinedRecents },
    TopLevelRoute.Updates.takeUnless { combinedRecents },
    TopLevelRoute.History.takeUnless { combinedRecents },
    TopLevelRoute.Browse,
    StatsTabRoute.takeIf { statsTab },
    TopLevelRoute.More,
)

/**
 * The tab [event] opens, sending on what it asks of that tab. A recents intent tells the combined tab which
 * mode it meant, and only when that tab is in the set, since nothing else reads it.
 */
internal fun tabFor(event: TabEvent, combinedRecents: Boolean, bus: ResultEventBus): TopLevelRoute = when (event) {
    is TabEvent.Library -> {
        // The query carries its content type, so it lands on that series' own chip.
        if (event.query != null) bus.sendResult(LibrarySearchEvent(event.query, event.contentType))
        TopLevelRoute.Library
    }
    TabEvent.Updates -> recentsTarget(combinedRecents, RecentsMode.UPDATES, bus)
    TabEvent.History -> recentsTarget(combinedRecents, RecentsMode.HISTORY, bus)
    is TabEvent.Browse -> {
        if (event.toExtensions) bus.sendResult(BrowseShowExtensionsEvent)
        TopLevelRoute.Browse
    }
    is TabEvent.More -> TopLevelRoute.More
}

private fun recentsTarget(combinedRecents: Boolean, mode: RecentsMode, bus: ResultEventBus): TopLevelRoute = when {
    combinedRecents -> RecentsTabRoute.also { bus.sendResult(RecentsModeEvent(mode)) }
    mode == RecentsMode.HISTORY -> TopLevelRoute.History
    else -> TopLevelRoute.Updates
}

/** Every tab the bar can show that [shown], the bar's current set, leaves out. */
internal fun droppedTabs(shown: List<TopLevelRoute>): List<TopLevelRoute> =
    (tabs(combinedRecents = true, statsTab = true) + tabs(combinedRecents = false, statsTab = true)).distinct() - shown

/** The page [event] opens over its tab: a series of either type, or the download queue. */
internal fun pageFor(event: TabEvent): NavKey? = when {
    event is TabEvent.Library && event.entryToOpen != null -> event.entryToOpen.detailsRoute()
    event is TabEvent.More && event.toDownloads -> DownloadQueueRoute
    else -> null
}

/** Where a tab that left the set sends the user, so nothing is left rendering unselected. */
internal fun replacementFor(tab: NavKey, combinedRecents: Boolean, statsTab: Boolean): TopLevelRoute? = when {
    combinedRecents && (tab == TopLevelRoute.Updates || tab == TopLevelRoute.History) -> RecentsTabRoute
    !combinedRecents && tab == RecentsTabRoute -> TopLevelRoute.Updates
    // The setting is reached through More, so that is where the user came from.
    !statsTab && tab == StatsTabRoute -> TopLevelRoute.More
    else -> null
}
// RK <--
