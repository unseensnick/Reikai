package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Strawberries theme, from Yōkai (Strawberry Daiquiri light, Chocolate Strawberries dark)
 * Yōkai's own colours are kept as it set them; the M3 roles it lacked are derived from
 * them at the matching tone. An on-colour under 3:1 contrast becomes black or white.
 *
 * Key colors (dark): primary 0xFFE14E4E, secondary 0xFFCC4444, background 0xFF1A1716
 */
internal object StrawberriesColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFE14E4E),
        onPrimary = Color(0xFF330303),
        primaryContainer = Color(0xFF92001C),
        onPrimaryContainer = Color(0xFFFFDAD6),
        inversePrimary = Color(0xFF9F1414),
        secondary = Color(0xFFCC4444), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFF92011C), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFF9BAB2), // Navigation bar selector icon
        tertiary = Color(0xFF99CC99), // Downloaded badge
        onTertiary = Color(0xFF000000), // Downloaded badge text
        tertiaryContainer = Color(0xFF215026),
        onTertiaryContainer = Color(0xFFBBF0BB),
        background = Color(0xFF1A1716),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF1A1716),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF2B2320), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFD7C2BF),
        surfaceTint = Color(0xFFE14E4E),
        inverseSurface = Color(0xFFE6E1E0),
        inverseOnSurface = Color(0xFF33302F),
        outline = Color(0xFFA08C8A),
        outlineVariant = Color(0xFF544341),
        surfaceContainerLowest = Color(0xFF251D1A),
        surfaceContainerLow = Color(0xFF28201D),
        surfaceContainer = Color(0xFF2B2320), // Navigation bar background
        surfaceContainerHigh = Color(0xFF322926),
        surfaceContainerHighest = Color(0xFF38302D),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFD31D3B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFEDAD8),
        onPrimaryContainer = Color(0xFF410003),
        inversePrimary = Color(0xFFEF8E9E),
        secondary = Color(0xFFED4A65), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFFFDADB), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF68101F), // Navigation bar selector icon
        tertiary = Color(0xFF99CC99), // Downloaded badge
        onTertiary = Color(0xFF000000), // Downloaded badge text
        tertiaryContainer = Color(0xFFBBF0BB),
        onTertiaryContainer = Color(0xFF032100),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF202020),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF202020),
        surfaceVariant = Color(0xFFF3E7E7), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFF544342),
        surfaceTint = Color(0xFFD31D3B),
        inverseSurface = Color(0xFF303030),
        inverseOnSurface = Color(0xFFF1F1F1),
        outline = Color(0xFF867371),
        outlineVariant = Color(0xFFD7C2C0),
        surfaceContainerLowest = Color(0xFFFCF0F0),
        surfaceContainerLow = Color(0xFFF7EBEB),
        surfaceContainer = Color(0xFFF3E7E7), // Navigation bar background
        surfaceContainerHigh = Color(0xFFEADEDE),
        surfaceContainerHighest = Color(0xFFE2D6D6),
    )
}
