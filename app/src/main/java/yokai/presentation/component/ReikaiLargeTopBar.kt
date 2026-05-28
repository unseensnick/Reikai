package yokai.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlin.math.roundToInt

// Alpha values pulled verbatim from FloatingToolbar.kt:22-23. Material 8-bit alpha (out of 255)
// — Compose Color.copy(alpha = Float) takes a 0..1 fraction.
private const val ACTION_COLOR_ALPHA = 200f / 255f          // ~0.784f — title + nav icon
private const val ACTION_COLOR_ALPHA_SECONDARY = 150f / 255f // ~0.588f — subtitle

/**
 * Unified large top bar for Reikai. Custom layout (not M3 LargeTopAppBar — that applies its
 * own surface-tint overlays and uses a different collapse curve). Mirrors the legacy library
 * bar (main_activity.xml `app_bar`) with its two-stage collapse:
 *
 * ```
 *   Expanded (scroll at top)               Compact (after scroll)
 *   ----------------------                 ----------------------
 *   [Status bar inset]                     [Status bar inset]
 *   [                          filter •••] [Search card · subtitle  filter •••]
 *   [Library headline                    ] [Manga | Light novels (tabs)       ]
 *   [Search card · subtitle              ]
 *   [Manga | Light novels (tabs)         ]
 * ```
 *
 * Pair with [androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior]
 * — the action row + headline collapse together as the user scrolls (matching the legacy
 * ExpandedAppBarLayout where `bigView` fades and `mainToolbar` swaps to the FloatingToolbar
 * (search card) on `updateAppBarAfterY`). The search card row + tabs stay pinned so the user
 * keeps a one-tap search affordance plus the tab switch even after deep scroll. The
 * trailing [actions] cross-fade from the top action row (when expanded) into the row next to
 * the search card (when collapsed), exactly like the legacy `setupSearchTBMenu` flow that
 * mirrors the main toolbar's menu onto the FloatingToolbar on scroll.
 *
 * Every visual value (sizes, paddings, colors, alphas, font weights) is lifted directly
 * from main_activity.xml + FloatingToolbar.kt — see inline comments tagging each source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReikaiLargeTopBar(
    title: String,
    searchCardTitle: String,
    onSearchCardClick: () -> Unit,
    modifier: Modifier = Modifier,
    searchCardSubtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    // Two-color set that SWAPS on collapse:
    //   Expanded: bar = ?attr/background, card = ?attr/colorPrimaryVariant
    //   Compact:  bar = ?attr/colorPrimaryVariant, card = ?attr/background
    // The card pill always reads as a distinct surface against the bar — the colors just
    // invert which side is darker as the bar collapses. Lerped per-frame off
    // `scrollBehavior.state.collapsedFraction` for a smooth mid-scroll transition.
    val baseBarColor = remember(context) { Color(context.getResourceColor(R.attr.background)) }
    val baseCardColor = remember(context) { Color(context.getResourceColor(R.attr.colorPrimaryVariant)) }
    val contentColor = remember(context) { Color(context.getResourceColor(R.attr.actionBarTintColor)) }

    val collapsedFraction = scrollBehavior?.state?.collapsedFraction ?: 0f
    val expandedAlpha = (1f - collapsedFraction).coerceIn(0f, 1f)
    val barColor = lerp(baseBarColor, baseCardColor, collapsedFraction)
    val cardColor = lerp(baseCardColor, baseBarColor, collapsedFraction)

    Surface(
        modifier = modifier,
        color = barColor,
        contentColor = contentColor,
        // Matches `android:elevation="0dp"` + `android:stateListAnimator="@null"` on the
        // legacy ExpandedAppBarLayout (main_activity.xml:35-36). No M3 surface-tint shift.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            // Collapsible top block = top action row + headline. Layout-time shrink so the
            // height genuinely changes (not just alpha) — search card + tabs reflow into the
            // freed space exactly like the legacy. Pair with exitUntilCollapsedScrollBehavior
            // so the bar stops at the compact state (search card + tabs visible) rather than
            // sliding fully off-screen.
            Column(
                modifier = Modifier
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0))
                        val fullHeight = placeable.height
                        scrollBehavior?.state?.let { state ->
                            val limit = -fullHeight.toFloat()
                            if (state.heightOffsetLimit != limit) {
                                state.heightOffsetLimit = limit
                            }
                        }
                        val offset = scrollBehavior?.state?.heightOffset?.roundToInt() ?: 0
                        val laidHeight = (fullHeight + offset).coerceAtLeast(0)
                        layout(placeable.width, laidHeight) {
                            placeable.placeWithLayer(0, offset)
                        }
                    },
            ) {
                // Top action row. Verbatim from CenteredToolbar at main_activity.xml:41-63
                // (height=?mainActionBarSize=56dp). Title slot is intentionally empty here —
                // the legacy CenteredToolbar's small-mode title is invisible on the library
                // (alpha=0 via ExpandedAppBarLayout's collapse math); when scroll collapses
                // this row, the search card below visually becomes the toolbar.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(end = 4.dp)
                        .alpha(expandedAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor.copy(alpha = ACTION_COLOR_ALPHA)) {
                        actions()
                    }
                }
                // Headline. Verbatim from `big_title` at main_activity.xml:91-108:
                //   style=?textAppearanceHeadlineLarge (M3 HeadlineLarge, 32sp Regular)
                //   paddingStart=16dp, paddingEnd=12dp
                //   maxLines=2
                //   parent big_toolbar paddingBottom=12dp (so 12dp gap below)
                //   textColor=?actionBarTintColor (full alpha — inherits via Surface contentColor)
                // The legacy `layout_marginTop=52dp` is intentionally NOT applied here: in the
                // legacy XML the 52dp pushes the title down past the action row's bottom edge
                // because both live inside the same AppBarLayout in absolute positions. In this
                // Compose Column the action row is laid out as a sibling above the title, so
                // the title naturally starts right under it — adding 52dp here would create a
                // visible empty band that doesn't exist in the legacy rendering.
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 12.dp, bottom = 12.dp)
                        .alpha(expandedAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Always-visible search card row. Verbatim from card_view + card_frame at
            // main_activity.xml:111-126:
            //   card_frame height=?mainActionBarSize=56dp
            //   card_view marginTop=4dp, marginBottom=4dp, marginStart=10dp, marginEnd=10dp
            //   cardCornerRadius=24dp, cardBackgroundColor=?colorPrimaryVariant
            // Inner card height = 56 - 4 - 4 = 48dp. When collapsed, the trailing actions
            // cross-fade in to the right of the card pill (NOT inside it — the legacy
            // FloatingToolbar's setupSearchTBMenu adds menu items as separate views sitting
            // beside the title in the toolbar's action area, not inside the card surface).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReikaiSearchCard(
                    title = searchCardTitle,
                    subtitle = searchCardSubtitle,
                    onClick = onSearchCardClick,
                    backgroundColor = cardColor,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                // Cross-fade actions in/out based on collapse fraction. AnimatedVisibility
                // (not just alpha) so the icons are not click-targets at alpha=0 — the
                // top-row icons handle clicks when expanded, the card-row icons handle clicks
                // when collapsed. Only one set is reachable at a time.
                AnimatedVisibility(
                    visible = collapsedFraction > 0.5f,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides contentColor.copy(alpha = ACTION_COLOR_ALPHA)) {
                            actions()
                        }
                    }
                }
            }

            below?.invoke()
        }
    }
}

/**
 * The tappable search-card surface that lives directly below the big title in
 * [ReikaiLargeTopBar]. Visually mirrors the legacy `FloatingToolbar` card
 * (main_activity.xml:128-194 + FloatingToolbar.kt:74-91):
 *
 *   - `cardCornerRadius=24dp`, `cardBackgroundColor=?colorPrimaryVariant`, `strokeWidth=0dp`
 *   - Leading magnifier glyph (ic_search_24dp = 24dp) tinted `actionBarTintColor` at
 *     alpha 200/255 (FloatingToolbar.setNavigationIconTint(actionColorAlpha)).
 *   - Title: `TextAppearance.FloatingTitle` (M3 TitleLarge with textSize=16sp override,
 *     Regular weight) tinted `actionBarTintColor` at alpha 200/255.
 *   - Optional subtitle: `?textAppearanceBodySmall` (M3 BodySmall, 12sp Regular) tinted
 *     `actionBarTintColor` at alpha 150/255.
 *
 * Tap on any non-action area dispatches [onClick] which the caller wires to its
 * search-active toggle.
 */
@Composable
fun ReikaiSearchCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backgroundColor: Color? = null,
) {
    val context = LocalContext.current
    // Defaults to ?attr/colorPrimaryVariant so the card stands alone when used outside the
    // bar's color-swap context. [ReikaiLargeTopBar] overrides this with a lerped color that
    // inverts toward ?attr/background as the bar collapses.
    val defaultBg = remember(context) { Color(context.getResourceColor(R.attr.colorPrimaryVariant)) }
    val resolvedBg = backgroundColor ?: defaultBg
    val contentColor = remember(context) { Color(context.getResourceColor(R.attr.actionBarTintColor)) }
    val titleColor = contentColor.copy(alpha = ACTION_COLOR_ALPHA)
    val subtitleColor = contentColor.copy(alpha = ACTION_COLOR_ALPHA_SECONDARY)

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = resolvedBg,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Matches FloatingToolbar's navigationIcon (ic_search_24dp, 24dp) tinted
            // actionBarTintColor at alpha 200/255 via setNavigationIconTint(actionColorAlpha).
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                // TextAppearance.FloatingTitle = M3 TitleLarge with textSize=16sp override.
                // M3 TitleLarge is Regular weight (FontWeight.Normal/400).
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    // ?textAppearanceBodySmall = M3 BodySmall (12sp Regular).
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
