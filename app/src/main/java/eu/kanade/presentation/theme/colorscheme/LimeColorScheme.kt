package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Lime theme, from Yōkai (Lime Time light, Flat Lime dark)
 * Yōkai's own colours are kept as it set them; the M3 roles it lacked are derived from
 * them at the matching tone. An on-colour under 3:1 contrast becomes black or white.
 *
 * Key colors (dark): primary 0xFF7CF7A5, secondary 0xFF4AF88A, background 0xFF202125
 */
internal object LimeColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF7CF7A5),
        onPrimary = Color(0xFF043314),
        primaryContainer = Color(0xFF015229),
        onPrimaryContainer = Color(0xFF80FBA9),
        inversePrimary = Color(0xFF09933C),
        secondary = Color(0xFF4AF88A), // Unread badge
        onSecondary = Color(0xFF0A2012), // Unread badge text
        secondaryContainer = Color(0xFF005225), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFCBFFE0), // Navigation bar selector icon
        tertiary = Color(0xFF304E51), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFF2E4C4F),
        onTertiaryContainer = Color(0xFFC7E9EC),
        background = Color(0xFF202125),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF202125),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF202125), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFBCCABF),
        surfaceTint = Color(0xFF7CF7A5),
        inverseSurface = Color(0xFFE1E2E8),
        inverseOnSurface = Color(0xFF2F3034),
        outline = Color(0xFF86948A),
        outlineVariant = Color(0xFF3E4A40),
        surfaceContainerLowest = Color(0xFF1A1B1F),
        surfaceContainerLow = Color(0xFF1D1E22),
        surfaceContainer = Color(0xFF202125), // Navigation bar background
        surfaceContainerHigh = Color(0xFF26272B),
        surfaceContainerHighest = Color(0xFF2D2E32),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF57BD79),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF92F8B0),
        onPrimaryContainer = Color(0xFF00210B),
        inversePrimary = Color(0xFFAEDFC0),
        secondary = Color(0xFF1DA750), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFF80FB9D), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF065124), // Navigation bar selector icon
        tertiary = Color(0xFF80CAD3), // Downloaded badge
        onTertiary = Color(0xFF000000), // Downloaded badge text
        tertiaryContainer = Color(0xFFA4EFF8),
        onTertiaryContainer = Color(0xFF011F23),
        background = Color(0xFFE9EFEB),
        onBackground = Color(0xFF1E1F1E),
        surface = Color(0xFFE9EFEB),
        onSurface = Color(0xFF1E1F1E),
        surfaceVariant = Color(0xFFD9ECE0), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFF3E4A40),
        surfaceTint = Color(0xFF57BD79),
        inverseSurface = Color(0xFF2D312E),
        inverseOnSurface = Color(0xFFECF2EE),
        outline = Color(0xFF6D7A70),
        outlineVariant = Color(0xFFBCCABF),
        surfaceContainerLowest = Color(0xFFE1F5E9),
        surfaceContainerLow = Color(0xFFDDF0E4),
        surfaceContainer = Color(0xFFD9ECE0), // Navigation bar background
        surfaceContainerHigh = Color(0xFFD1E3D8),
        surfaceContainerHighest = Color(0xFFC8DBCF),
    )
}
