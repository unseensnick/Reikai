package eu.kanade.tachiyomi.ui.library

import android.os.Bundle
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.system.getResourceColor
import yokai.i18n.MR
import yokai.presentation.library.NovelLibraryTabContent
import yokai.presentation.library.novels.NovelLibraryScreenModel

/**
 * Hosts the Compose novel library page as a child of `LibraryHostController` on the legacy
 * library path. The same composable body powers the Compose-library tab in
 * [yokai.presentation.library.LibraryScreen].
 *
 * The legacy app bar (which contains `mainTabs`) is hidden here so the Compose body owns
 * its own chrome end-to-end. To preserve the Manga / Light novels switch, we inject a Compose
 * [PrimaryTabRow] into the composable's `tabRow` slot — tapping the Manga tab calls back into
 * [LibraryHostController.selectTab] which re-shows the legacy bar and swaps to the manga child.
 *
 * Implements the marker interfaces [RootSearchInterface] and [FloatingSearchInterface] only so
 * `MainActivity`'s root-controller checks treat it as a normal search-capable surface. Search
 * is rendered internally by `LibraryContent`, not via the activity's `searchToolbar`.
 */
class NovelLibraryComposeController(bundle: Bundle? = null) :
    BaseComposeController(bundle),
    RootSearchInterface,
    FloatingSearchInterface {

    // The ComposeView spans the full screen (no bottom padding applied here). LibraryContent
    // owns the bottom-nav reservation via a reactive Modifier.padding tied to the nav's
    // translationY, so the LazyGrid renders into the freed pixels as the user scrolls the nav
    // off. Applying a fixed nav.height padding here as well would double-reserve.

    @Composable
    override fun ScreenContent() {
        // Override LocalRouter with the parent's router (= MainActivity's main router) so
        // pushes from inside the composable (e.g. NovelDetailsController via a cover tap)
        // land full-screen instead of inside the host's tab container. BaseComposeController's
        // default provides `router` which here is the child router.
        val targetRouter = parentController?.router ?: router
        val host = parentController as? LibraryHostController
        CompositionLocalProvider(LocalRouter provides targetRouter) {
            // Voyager's rememberScreenModel needs a Screen scope. Wrap in a no-op anonymous
            // Screen (same pattern as LnPluginBrowseContent at LnPluginBrowseContent.kt:84) so
            // we don't need a full Navigator here — there is nothing to navigate inside the
            // novel tab.
            val screenAnchor = remember {
                object : Screen {
                    override val key: String = "novel-library-tab-anchor"
                    @Composable override fun Content() {}
                }
            }
            with(screenAnchor) {
                val screenModel = rememberScreenModel { NovelLibraryScreenModel() }
                val tabRow: @Composable () -> Unit = {
                    val tabRowContext = LocalContext.current
                    val tabRowContainerColor = remember(tabRowContext) {
                        Color(tabRowContext.getResourceColor(R.attr.background))
                    }
                    PrimaryTabRow(
                        selectedTabIndex = LibraryHostController.TAB_NOVELS,
                        containerColor = tabRowContainerColor,
                    ) {
                        Tab(
                            selected = false,
                            onClick = { host?.selectTab(LibraryHostController.TAB_MANGA) },
                            text = { Text(stringResource(MR.strings.manga)) },
                        )
                        Tab(
                            selected = true,
                            onClick = {},
                            text = { Text(stringResource(MR.strings.light_novels)) },
                        )
                    }
                }
                NovelLibraryTabContent(screenModel = screenModel, tabRow = tabRow)
            }
        }
    }
}
