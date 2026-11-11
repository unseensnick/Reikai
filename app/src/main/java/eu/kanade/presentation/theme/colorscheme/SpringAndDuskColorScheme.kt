package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Spring and Dusk theme, from Yōkai (Spring Blossom light, Midnight Dusk dark)
 * Yōkai's own colours are kept as it set them; the M3 roles it lacked are derived from
 * them at the matching tone. An on-colour under 3:1 contrast becomes black or white.
 *
 * Key colors (dark): primary 0xFFE570A0, secondary 0xFFF02475, background 0xFF16151D
 */
internal object SpringAndDuskColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFE570A0),
        onPrimary = Color(0xFF370318),
        primaryContainer = Color(0xFF87144F),
        onPrimaryContainer = Color(0xFFFED9E5),
        inversePrimary = Color(0xFF8D0C3F),
        secondary = Color(0xFFF02475), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFF8F0141), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFFFC1DA), // Navigation bar selector icon
        tertiary = Color(0xFF99CC99), // Downloaded badge
        onTertiary = Color(0xFF000000), // Downloaded badge text
        tertiaryContainer = Color(0xFF215026),
        onTertiaryContainer = Color(0xFFBBF0BB),
        background = Color(0xFF16151D),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF16151D),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF272026), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFD5C2C8),
        surfaceTint = Color(0xFFE570A0),
        inverseSurface = Color(0xFFE3E1ED),
        inverseOnSurface = Color(0xFF302F38),
        outline = Color(0xFF9E8C92),
        outlineVariant = Color(0xFF534248),
        surfaceContainerLowest = Color(0xFF211A20),
        surfaceContainerLow = Color(0xFF241D23),
        surfaceContainer = Color(0xFF272026), // Navigation bar background
        surfaceContainerHigh = Color(0xFF2D262C),
        surfaceContainerHighest = Color(0xFF342D33),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFA149BF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF7D8FF),
        onPrimaryContainer = Color(0xFF330045),
        inversePrimary = Color(0xFFCD95E0),
        secondary = Color(0xFFC43C97), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFFFD8ED), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF4C163A), // Navigation bar selector icon
        tertiary = Color(0xFF99CC99), // Downloaded badge
        onTertiary = Color(0xFF000000), // Downloaded badge text
        tertiaryContainer = Color(0xFFBBF0BB),
        onTertiaryContainer = Color(0xFF032100),
        background = Color(0xFFF7F4F8),
        onBackground = Color(0xFF3F2643),
        surface = Color(0xFFF7F4F8),
        onSurface = Color(0xFF3F2643),
        surfaceVariant = Color(0xFFEFE3F3), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFF4D444F),
        surfaceTint = Color(0xFFA149BF),
        inverseSurface = Color(0xFF323033),
        inverseOnSurface = Color(0xFFF3F0F4),
        outline = Color(0xFF7E7480),
        outlineVariant = Color(0xFFCEC3D0),
        surfaceContainerLowest = Color(0xFFF8ECFC),
        surfaceContainerLow = Color(0xFFF3E7F7),
        surfaceContainer = Color(0xFFEFE3F3), // Navigation bar background
        surfaceContainerHigh = Color(0xFFE6DBEA),
        surfaceContainerHighest = Color(0xFFDED2E2),
    )
}
