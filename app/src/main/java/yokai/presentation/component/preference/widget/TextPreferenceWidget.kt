package yokai.presentation.component.preference.widget

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import yokai.util.secondaryItemAlpha

@Composable
fun TextPreferenceWidget(
    modifier: Modifier = Modifier,
    title: String? = null,
    /** Styled title that supersedes [title] (e.g. carrying a BETA tag suffix). */
    titleAnnotated: androidx.compose.ui.text.AnnotatedString? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    widget: @Composable (() -> Unit)? = null,
    onPreferenceClick: (() -> Unit)? = null,
    /**
     * When false, the click handler is dropped and the row is rendered at reduced alpha.
     * Mirrors the legacy `View.isEnabled = false` look for dependent prefs (e.g. staggered grid
     * row while uniform grid is on, [eu.kanade.tachiyomi.ui.library.display.LibraryDisplayView]).
     */
    enabled: Boolean = true,
) {
    BasePreferenceWidget(
        modifier = if (enabled) modifier else modifier.alpha(0.38f),
        title = title,
        titleAnnotated = titleAnnotated,
        subcomponent = if (!subtitle.isNullOrBlank()) {
            {
                Text(
                    text = subtitle,
                    modifier = Modifier
                        .padding(horizontal = PrefsHorizontalPadding)
                        .secondaryItemAlpha(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 10,
                )
            }
        } else {
            null
        },
        icon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    tint = iconTint,
                    contentDescription = null,
                )
            }
        } else {
            null
        },
        onClick = onPreferenceClick.takeIf { enabled },
        widget = widget,
    )
}
