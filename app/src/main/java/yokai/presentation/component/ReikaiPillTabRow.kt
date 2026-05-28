package yokai.presentation.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor

/**
 * Pill-style tab row mirroring the legacy `Theme.Widget.Tabs.Highlight` style
 * (styles.xml:223-234 + drawable/tab_highlight_indicator.xml). Every dimension and color
 * lifted verbatim from those sources:
 *
 *   - Container color matches the parent bar (`?attr/colorPrimaryVariant`) so the tab row
 *     blends with [ReikaiLargeTopBar]'s surface instead of cutting it with a separate band.
 *   - Pill drawn behind the active tab: `?attr/tabHighlightBackground` fill, 14sp corner
 *     radius, 28sp tall, 4dp horizontal inset from the tab edges (matches the layer-list
 *     in tab_highlight_indicator.xml).
 *   - Text colors from color/tabs_selector_alt.xml:
 *     selected = `?attr/colorSecondaryVariant`, default = `?attr/actionBarTintColor` at
 *     60% alpha.
 *   - Text appearance from TextAppearance.Widget.Tab (styles.xml:38-41): no all caps, no
 *     letter spacing, ~14sp button text. Compose's default Text omits caps + spacing.
 *   - No ripple: `tabRippleColor=@android:color/transparent` on the legacy style.
 */
@Composable
fun ReikaiPillTabRow(
    selectedTabIndex: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Transparent so the parent bar's (lerped) background color shows through. [ReikaiLargeTopBar]
    // swaps its bar color between `?attr/background` (expanded) and `?attr/colorPrimaryVariant`
    // (compact) based on scroll; the tab row needs to follow that swap, which transparency
    // gives us for free without re-implementing the lerp here.
    val containerColor = Color.Transparent
    val pillColor = remember(context) { Color(context.getResourceColor(R.attr.tabHighlightBackground)) }
    val selectedTextColor = remember(context) { Color(context.getResourceColor(R.attr.colorSecondaryVariant)) }
    val unselectedTextColor = remember(context) {
        Color(context.getResourceColor(R.attr.actionBarTintColor)).copy(alpha = 0.6f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selectedTabIndex
                val animatedPillColor by animateColorAsState(
                    targetValue = if (isSelected) pillColor else Color.Transparent,
                    animationSpec = tween(durationMillis = 200),
                    label = "pill_$index",
                )
                val animatedTextColor by animateColorAsState(
                    targetValue = if (isSelected) selectedTextColor else unselectedTextColor,
                    animationSpec = tween(durationMillis = 200),
                    label = "text_$index",
                )
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 4dp horizontal inset matches `tab_highlight_indicator.xml`'s
                        // `android:start="4dp" android:end="4dp"`. Vertical inset centers a
                        // 28sp pill inside a 48dp tab row.
                        .padding(horizontal = 4.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(animatedPillColor)
                        .clickable(
                            interactionSource = interactionSource,
                            // Matches `tabRippleColor=@android:color/transparent` in the
                            // legacy `Theme.Widget.Tabs.Highlight` style — no ripple effect.
                            indication = null,
                            onClick = { onTabSelected(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = animatedTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
