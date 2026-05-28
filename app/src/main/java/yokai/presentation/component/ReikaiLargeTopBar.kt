package yokai.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isTablet
import kotlin.math.roundToInt

// Alpha values pulled verbatim from FloatingToolbar.kt:22-23. Material 8-bit alpha (out of 255)
// — Compose Color.copy(alpha = Float) takes a 0..1 fraction.
private const val ACTION_COLOR_ALPHA = 200f / 255f          // ~0.784f — title + nav icon
private const val ACTION_COLOR_ALPHA_SECONDARY = 150f / 255f // ~0.588f — subtitle

/**
 * Unified large top bar for Reikai. Custom layout (not M3 LargeTopAppBar — that applies its
 * own surface-tint overlays and uses a different collapse curve). Mirrors the legacy library
 * bar (refs/yokai main_activity.xml + ExpandedAppBarLayout) with form-factor-specific scroll:
 *
 * ```
 *   Expanded (scroll at top)      Compact (after scroll)      Phone full-hide
 *   ----------------------        ----------------------      ---------------
 *   [Status bar inset]            [Status bar inset]          (bar gone, content uses
 *   [           filter •••]       [card · sub  filter •••]    full viewport — system
 *   [Library headline    ]        [Manga | Light novels]      status bar stays as
 *   [Search card · sub  ]                                     overlay)
 *   [Manga | Light novels]
 * ```
 *
 * **Form-factor scroll** (mirrors `ExpandedAppBarLayout.minTabletHeight:121-130`):
 *   - Phone: bar hides entirely on continued scroll-down (slides up + shrinks to height 0
 *     including status bar inset area).
 *   - Tablet: bar collapses only to the `card + tabs` height (the minTabletHeight clamp).
 *     The card and tabs stay pinned at the top.
 *
 *   Pair with [androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior].
 *   The bar's [SubcomposeLayout] sets `heightOffsetLimit` per form factor:
 *     - Tablet: `-collapsibleHeight` (matches upstream's "collapse small, never hide" behavior).
 *     - Phone: `-totalHeight` (everything including status bar area can slide off).
 *
 * **Color** (matches `MainActivity.setFloatingToolbar:828-831`): bar lerps
 * `Color.Transparent` → `?colorSurface` based on how far the COLLAPSIBLE portion has
 * collapsed (not the total bar). Card is constant `?colorPrimaryVariant`.
 *
 * **Headline alpha curve** from `ExpandedAppBarLayout.kt:347`:
 *   `alpha = (bigHeight + newY*2) / bigHeight + 0.45`, clamped [0, 1] — simplified here to
 *   `1.45 - 2 * collapsibleFraction`. Holds at 1 through ~22% of collapsible scroll, then
 *   fades to 0 by ~72%.
 *
 * **Action icons** (mirrors upstream `setupSearchTBMenu` + Toolbar menu rendering): when the
 * collapsible portion is past 50% collapsed, the top-row icons fade out and a duplicate set
 * fades INSIDE the search card pill on the right (via the card's `trailing` slot — Toolbar
 * action items in upstream render inside the FloatingToolbar's content area, which is
 * inside the card). Same cross-fade on phone and tablet.
 *
 * **Search expand in place** (mirrors upstream `MiniSearchView.onActionViewExpanded`): when
 * [searchActive] is true, the bar is pinned to the compact-equivalent state — collapsible
 * block fully slid off, headline + action-row icons hidden, bar color = `?colorSurface`,
 * search card visible at top — and the card's content swaps to back arrow + TextField +
 * clear button (same card height + position). This guarantees the headline never re-appears
 * when the user enters search from compact state.
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
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onSearchClose: () -> Unit = {},
    searchHint: String = "",
    onCollapsibleHeightChange: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val barColorCompact = remember(context) { Color(context.getResourceColor(R.attr.colorSurface)) }
    val cardColor = remember(context) { Color(context.getResourceColor(R.attr.colorPrimaryVariant)) }
    val contentColor = remember(context) { Color(context.getResourceColor(R.attr.actionBarTintColor)) }
    val isTablet = remember(context) { context.isTablet() }

    // Capture the measured collapsible block height (action row + headline) so the alpha
    // curves outside SubcomposeLayout can derive `collapsibleFraction` independently from M3's
    // built-in `state.collapsedFraction` — that value scales by `heightOffsetLimit` which on
    // phone is `-totalHeight`, so the alphas would otherwise animate too slowly. Init to 1 to
    // avoid divide-by-zero on the first frame; the layout block updates it from the real
    // measurement and the guard suppresses recomposition loops.
    var measuredCollapsibleHeightPx by remember { mutableIntStateOf(1) }
    val rawOffset = scrollBehavior?.state?.heightOffset?.roundToInt() ?: 0
    // `collapsibleFraction` = how far the action row + headline have collapsed (0 expanded,
    // 1 fully collapsed). Drives every alpha curve. When searchActive, force to 1 so the bar
    // immediately reads as compact (matches upstream's compactSearchMode pin).
    val collapsibleFraction = if (searchActive) {
        1f
    } else {
        (-rawOffset.toFloat() / measuredCollapsibleHeightPx).coerceIn(0f, 1f)
    }

    val barColor = lerp(Color.Transparent, barColorCompact, collapsibleFraction)
    // Upstream curve at ExpandedAppBarLayout.kt:347.
    val headlineAlpha = (1.45f - 2f * collapsibleFraction).coerceIn(0f, 1f)
    val actionRowAlpha = (1f - collapsibleFraction).coerceIn(0f, 1f)
    // Action icons cross-fade: top row fades out, in-card trailing fades in past 50% scroll.
    // Both forced to 0 during search (clear button replaces them).
    val inCardIconAlpha = if (searchActive) 0f else (collapsibleFraction * 2f - 1f).coerceIn(0f, 1f)

    Surface(
        modifier = modifier,
        color = barColor,
        contentColor = contentColor,
        // Matches `android:elevation="0dp"` + `android:stateListAnimator="@null"` on the
        // legacy ExpandedAppBarLayout (main_activity.xml:35-36). No M3 surface-tint shift.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        // Status bar inset handling differs per form factor:
        //   - Tablet: status bar inset is PINNED at the top via outer `statusBarsPadding`. It
        //     stays painted by the Surface even when the bar collapses to compact (matches
        //     upstream's minTabletHeight behavior — the small toolbar surface stays at top).
        //   - Phone: status bar inset is included INSIDE the SubcomposeLayout as a slot at
        //     the top of the slide, so when the bar fully hides on scroll-down the inset area
        //     goes off-screen too (no leftover band of `?colorSurface` painting the status bar
        //     area). Scaffold's contentWindowInsets still reserves the system status bar for
        //     content, so the system overlay sits over the activity background, not over the
        //     bar's color.
        Column(modifier = if (isTablet) Modifier.statusBarsPadding() else Modifier) {
            SubcomposeLayout(modifier = Modifier.clipToBounds()) { constraints ->
                val childConstraints = constraints.copy(minHeight = 0)

                // Phone-only: empty Spacer sized to the status bar inset. Slides with the rest
                // of the bar so the bar's measured height truly reaches 0 on full-hide.
                val statusBarInsetPlaceables = if (isTablet) {
                    emptyList()
                } else {
                    subcompose("statusBarInset") {
                        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    }.map { it.measure(childConstraints) }
                }

                // Subcompose the collapsible block (action row + headline) first so we can
                // measure its height before subcomposing the always-visible block (which
                // needs `inCardIconAlpha` already computed).
                val collapsiblePlaceables = subcompose("collapsible") {
                    ActionRow(
                        actions = actions,
                        contentColor = contentColor,
                        alpha = actionRowAlpha,
                    )
                    // Headline. From `big_title` at main_activity.xml:91-108:
                    //   style=?textAppearanceHeadlineLarge (M3 HeadlineLarge, 32sp Regular)
                    //   paddingStart=16dp, paddingEnd=12dp
                    //   maxLines=2
                    //   parent big_toolbar paddingBottom=12dp
                    //   textColor=?actionBarTintColor (full alpha — inherits via Surface contentColor)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier
                            .padding(start = 16.dp, end = 12.dp, bottom = 12.dp)
                            .alpha(headlineAlpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }.map { it.measure(childConstraints) }

                val collapsibleHeight = collapsiblePlaceables.sumOf { it.height }
                if (measuredCollapsibleHeightPx != collapsibleHeight && collapsibleHeight > 0) {
                    measuredCollapsibleHeightPx = collapsibleHeight
                    // Notify the caller so an external scroll behavior (e.g.
                    // CompactPivotScrollBehavior on phone) can know the compact threshold.
                    onCollapsibleHeightChange?.invoke(collapsibleHeight)
                }

                // Subcompose the always-visible block (search card + tabs). `inCardIconAlpha`
                // is already in scope so the trailing slot can render with the right alpha.
                val alwaysVisiblePlaceables = subcompose("alwaysVisible") {
                    // Search card row. From card_view + card_frame at main_activity.xml:111-126:
                    //   card_frame height=?mainActionBarSize=56dp
                    //   card_view marginTop=4dp, marginBottom=4dp, marginStart=10dp, marginEnd=10dp
                    //   cardCornerRadius=24dp, cardBackgroundColor=?colorPrimaryVariant
                    // Inner card height = 56 - 4 - 4 = 48dp.
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
                            searchActive = searchActive,
                            searchQuery = searchQuery,
                            onSearchQueryChange = onSearchQueryChange,
                            onSearchClose = onSearchClose,
                            searchHint = searchHint,
                            trailing = if (inCardIconAlpha > 0f) {
                                {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.alpha(inCardIconAlpha),
                                    ) {
                                        CompositionLocalProvider(LocalContentColor provides contentColor.copy(alpha = ACTION_COLOR_ALPHA)) {
                                            actions()
                                        }
                                    }
                                }
                            } else null,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    below?.invoke()
                }.map { it.measure(childConstraints) }

                val alwaysVisibleHeight = alwaysVisiblePlaceables.sumOf { it.height }
                val statusBarInsetHeight = statusBarInsetPlaceables.sumOf { it.height }
                val totalHeight = statusBarInsetHeight + collapsibleHeight + alwaysVisibleHeight

                // Form-factor scroll limit:
                //   Tablet: -collapsibleHeight (mirrors upstream minTabletHeight clamp — bar
                //     never shrinks below alwaysVisible height). Status bar inset is outside
                //     the slide (pinned on the outer Column) so it stays visible too.
                //   Phone:  -totalHeight = -(statusBarInset + collapsible + alwaysVisible).
                //     Bar slides fully off-screen including the status bar inset area, so no
                //     band of `?colorSurface` remains.
                scrollBehavior?.state?.let { state ->
                    val limit = if (isTablet) -collapsibleHeight.toFloat() else -totalHeight.toFloat()
                    if (state.heightOffsetLimit != limit) {
                        state.heightOffsetLimit = limit
                    }
                }

                // When searching, pin the bar to the compact-equivalent offset (collapsible
                // fully slid off, alwaysVisible at top). Headline + action row hidden, card
                // visible at top — matches upstream's compactSearchMode pin.
                val effectiveOffset = if (searchActive) -collapsibleHeight else rawOffset
                val laidHeight = (totalHeight + effectiveOffset).coerceAtLeast(0)

                layout(constraints.maxWidth, laidHeight) {
                    var y = effectiveOffset
                    statusBarInsetPlaceables.forEach { placeable ->
                        placeable.placeWithLayer(0, y)
                        y += placeable.height
                    }
                    collapsiblePlaceables.forEach { placeable ->
                        placeable.placeWithLayer(0, y)
                        y += placeable.height
                    }
                    alwaysVisiblePlaceables.forEach { placeable ->
                        placeable.placeWithLayer(0, y)
                        y += placeable.height
                    }
                }
            }
        }
    }
}

/**
 * The top action row. Height matches the legacy `CenteredToolbar`'s `?mainActionBarSize`
 * (56dp) at main_activity.xml:45.
 */
@Composable
private fun ActionRow(
    actions: @Composable RowScope.() -> Unit,
    contentColor: Color,
    alpha: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(end = 4.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor.copy(alpha = ACTION_COLOR_ALPHA)) {
            actions()
        }
    }
}

/**
 * The tappable search-card surface. Visually mirrors the legacy `FloatingToolbar` card
 * (main_activity.xml:128-194 + FloatingToolbar.kt:74-91):
 *
 *   - `cardCornerRadius=24dp`, `cardBackgroundColor=?colorPrimaryVariant`, `strokeWidth=0dp`.
 *   - Leading magnifier (ic_search_24dp, 24dp) tinted `actionBarTintColor` at alpha 200/255.
 *   - Title: `TextAppearance.FloatingTitle` (M3 TitleLarge with textSize=16sp, Regular
 *     weight) tinted `actionBarTintColor` at alpha 200/255.
 *   - Optional subtitle: M3 BodySmall (12sp Regular) tinted `actionBarTintColor` at
 *     alpha 150/255.
 *   - Optional [trailing] slot: action icons that render INSIDE the pill on the right —
 *     mirrors upstream where the FloatingToolbar's menu items render as Toolbar action
 *     items at the right of the content area.
 *
 * **Search-active mode** ([searchActive] = true): the card transforms in place — back
 * arrow (replacing the leading magnifier) + full-width [BasicTextField] + optional clear
 * button (when the query is non-empty) — at the same height + position. Auto-focuses the
 * text field via [LaunchedEffect] when it enters this state. Matches upstream's
 * `MiniSearchView.onActionViewExpanded()` flow where the search view sets width=MATCH_PARENT
 * to fill the toolbar's content area while other menu items collapse out.
 */
@Composable
fun ReikaiSearchCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backgroundColor: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onSearchClose: () -> Unit = {},
    searchHint: String = "",
) {
    val context = LocalContext.current
    val defaultBg = remember(context) { Color(context.getResourceColor(R.attr.colorPrimaryVariant)) }
    val resolvedBg = backgroundColor ?: defaultBg
    val contentColor = remember(context) { Color(context.getResourceColor(R.attr.actionBarTintColor)) }
    val titleColor = contentColor.copy(alpha = ACTION_COLOR_ALPHA)
    val subtitleColor = contentColor.copy(alpha = ACTION_COLOR_ALPHA_SECONDARY)

    val surfaceOnClick: () -> Unit = if (searchActive) ({}) else onClick

    Surface(
        onClick = surfaceOnClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = resolvedBg,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        if (searchActive) {
            SearchCardActiveContent(
                query = searchQuery,
                hint = searchHint,
                onQueryChange = onSearchQueryChange,
                onClose = onSearchClose,
                titleColor = titleColor,
            )
        } else {
            SearchCardDefaultContent(
                title = title,
                subtitle = subtitle,
                titleColor = titleColor,
                subtitleColor = subtitleColor,
                trailing = trailing,
            )
        }
    }
}

@Composable
private fun SearchCardDefaultContent(
    title: String,
    subtitle: String?,
    titleColor: Color,
    subtitleColor: Color,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
    }
}

@Composable
private fun SearchCardActiveContent(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    titleColor: Color,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = titleColor,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 16.sp,
                color = titleColor,
            ),
            cursorBrush = SolidColor(titleColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = hint,
                            fontSize = 16.sp,
                            color = titleColor.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = titleColor,
                )
            }
        }
    }
}
