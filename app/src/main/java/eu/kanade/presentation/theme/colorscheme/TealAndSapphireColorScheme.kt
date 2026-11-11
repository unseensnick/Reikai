package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Teal and Sapphire theme, from Yōkai (Teal Ocean light, Sapphire Dusk dark)
 * Yōkai's own colours are kept as it set them; the M3 roles it lacked are derived from
 * them at the matching tone. An on-colour under 3:1 contrast becomes black or white.
 *
 * Key colors (dark): primary 0xFF80B8D1, secondary 0xFF589AB8, background 0xFF14191B
 */
internal object TealAndSapphireColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF80B8D1),
        onPrimary = Color(0xFF02212C),
        primaryContainer = Color(0xFF014D63),
        onPrimaryContainer = Color(0xFFBCE9FE),
        inversePrimary = Color(0xFF1D698B),
        secondary = Color(0xFF589AB8), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFF014D64), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFB5EBFF), // Navigation bar selector icon
        tertiary = Color(0xFF99CC99), // Downloaded badge
        onTertiary = Color(0xFF141A14), // Downloaded badge text
        tertiaryContainer = Color(0xFF215026),
        onTertiaryContainer = Color(0xFFBBF0BB),
        background = Color(0xFF14191B),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF14191B),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1D2528), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFB7C9D3),
        surfaceTint = Color(0xFF80B8D1),
        inverseSurface = Color(0xFFDDE4E6),
        inverseOnSurface = Color(0xFF2C3133),
        outline = Color(0xFF82939C),
        outlineVariant = Color(0xFF394951),
        surfaceContainerLowest = Color(0xFF171F22),
        surfaceContainerLow = Color(0xFF1A2225),
        surfaceContainer = Color(0xFF1D2528), // Navigation bar background
        surfaceContainerHigh = Color(0xFF232B2E),
        surfaceContainerHighest = Color(0xFF2A3235),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF5F9C96),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFAFEEE7),
        onPrimaryContainer = Color(0xFF01201E),
        inversePrimary = Color(0xFFA1E1DB),
        secondary = Color(0xFF05B1A4), // Unread badge
        onSecondary = Color(0xFF000000), // Unread badge text
        secondaryContainer = Color(0xFF71F8E9), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF12524B), // Navigation bar selector icon
        tertiary = Color(0xFF106010), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFFABF59B),
        onTertiaryContainer = Color(0xFF0B2000),
        background = Color(0xFFF0F7F7),
        onBackground = Color(0xFF254441),
        surface = Color(0xFFF0F7F7),
        onSurface = Color(0xFF254441),
        surfaceVariant = Color(0xFFD4F1E6), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFF374A48),
        surfaceTint = Color(0xFF5F9C96),
        inverseSurface = Color(0xFF2C3131),
        inverseOnSurface = Color(0xFFEBF2F2),
        outline = Color(0xFF677B79),
        outlineVariant = Color(0xFFB5CBC8),
        surfaceContainerLowest = Color(0xFFDCFAEF),
        surfaceContainerLow = Color(0xFFD8F5EA),
        surfaceContainer = Color(0xFFD4F1E6), // Navigation bar background
        surfaceContainerHigh = Color(0xFFCCE8DE),
        surfaceContainerHighest = Color(0xFFC3E0D5),
    )
}
