package yokai.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlin.math.roundToInt

/**
 * The single small-variant top bar used across Reikai's Compose surfaces. Mirrors what the
 * library compact branch (LibraryContent.kt) had inline: legacy-attr color pinning by default,
 * optional scroll behavior, and an optional [below] slot that participates in the same
 * scroll-driven collapse as the bar itself (used for the library's tab row).
 *
 * The slot-based [title] / [navigationIcon] / [actions] mirror M3 [TopAppBar]'s API, so
 * callers can pass arbitrary content (custom title styles, conditional actions, etc.).
 * For simple action lists, use [AppBarActions] with the sealed `AppBar.AppBarAction` types.
 *
 * When [below] is non-null, this composable drives the collapse itself instead of letting
 * M3's internal `heightOffset` shrink only the bar's content. The wrapper measures the full
 * stack (status bar inset + bar + below), overrides `heightOffsetLimit` to that height, and
 * shrinks layout-time so the content below the bar moves up as the bar collapses. M3's
 * default limit excludes the inset zone, which would leave a painted strip at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReikaiTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = ReikaiTopBarDefaults.colors(),
    below: (@Composable () -> Unit)? = null,
) {
    if (below == null) {
        TopAppBar(
            modifier = modifier,
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            colors = colors,
        )
        return
    }

    // The inner TopAppBar does NOT receive scrollBehavior. We don't want it to self-shrink
    // (that would leave the status bar inset zone painted) and we don't want double offsets.
    // The wrapper drives the entire collapse via layout-time shrink + placement.
    Column(
        modifier = modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val state = scrollBehavior?.state
                if (state != null) {
                    val fullHeight = -placeable.height.toFloat()
                    if (state.heightOffsetLimit != fullHeight) {
                        state.heightOffsetLimit = fullHeight
                    }
                }
                val offset = state?.heightOffset?.roundToInt() ?: 0
                val height = (placeable.height + offset).coerceAtLeast(0)
                layout(placeable.width, height) {
                    placeable.placeWithLayer(0, offset)
                }
            },
    ) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
        )
        below()
    }
}

/**
 * Convenience overload for plain-string titles. Renders the title with M3's default title
 * style (the slot's CompositionLocal). For custom styles or subtitles, pass a composable title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReikaiTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = ReikaiTopBarDefaults.colors(),
    below: (@Composable () -> Unit)? = null,
) = ReikaiTopBar(
    title = { Text(text = title) },
    modifier = modifier,
    navigationIcon = navigationIcon,
    actions = actions,
    scrollBehavior = scrollBehavior,
    colors = colors,
    below = below,
)

object ReikaiTopBarDefaults {
    /**
     * Container + content colors pinned to the legacy theme attrs (`R.attr.background`,
     * `R.attr.actionBarTintColor`) so Compose surfaces match the legacy library chrome across
     * every Reikai theme. M3's ColorScheme tokens don't surface these attrs directly.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun colors(): TopAppBarColors {
        val context = LocalContext.current
        val container = remember(context) { Color(context.getResourceColor(R.attr.background)) }
        val content = remember(context) { Color(context.getResourceColor(R.attr.actionBarTintColor)) }
        return TopAppBarDefaults.topAppBarColors(
            containerColor = container,
            scrolledContainerColor = container,
            titleContentColor = content,
            navigationIconContentColor = content,
            actionIconContentColor = content,
        )
    }
}
