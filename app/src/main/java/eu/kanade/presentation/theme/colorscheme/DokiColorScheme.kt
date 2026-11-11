package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Doki theme, from Yōkai (by ziro, dark only)
 * Yōkai's own colours are kept as it set them; the M3 roles it lacked are derived from
 * them at the matching tone. An on-colour under 3:1 contrast becomes black or white.
 *
 * Key colors (dark): primary 0xFFFDE289, secondary 0xFFEABD62, background 0xFF040716
 */
internal object DokiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFDE289),
        onPrimary = Color(0xFF070F2C),
        primaryContainer = Color(0xFF534600),
        onPrimaryContainer = Color(0xFFFCE188),
        inversePrimary = Color(0xFFEBBA6C),
        secondary = Color(0xFFEABD62), // Unread badge
        onSecondary = Color(0xFF1E180D), // Unread badge text
        secondaryContainer = Color(0xFF5B4301), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFF4CC8A), // Navigation bar selector icon
        tertiary = Color(0xFF99CC99), // Downloaded badge
        onTertiary = Color(0xFF000000), // Downloaded badge text
        tertiaryContainer = Color(0xFF215026),
        onTertiaryContainer = Color(0xFFBBF0BB),
        background = Color(0xFF040716),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF040716),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF070F2C), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFCCC6B8),
        surfaceTint = Color(0xFFFDE289),
        inverseSurface = Color(0xFFE0E2ED),
        inverseOnSurface = Color(0xFF2E3039),
        outline = Color(0xFF969083),
        outlineVariant = Color(0xFF4B463A),
        surfaceContainerLowest = Color(0xFF000426),
        surfaceContainerLow = Color(0xFF040A29),
        surfaceContainer = Color(0xFF070F2C), // Navigation bar background
        surfaceContainerHigh = Color(0xFF0D1632),
        surfaceContainerHighest = Color(0xFF141C39),
    )

    // Dark only, as Yōkai drew it.
    override val lightScheme = darkScheme
}
