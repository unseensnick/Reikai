package yokai.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import com.google.accompanist.themeadapter.material3.createMdc3Theme
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Wraps every Compose surface in the app's active theme. The theme is keyed off the user's
 * light / dark pref and the system dark-mode flag; [createMdc3Theme] reads the active XML
 * theme attrs the user picked and returns a Material 3 [ColorScheme].
 *
 * Perf notes: this composable is on every recomposition path. The theme pref read uses
 * [collectAsState] so reads are cached and recomposition only happens on actual pref changes.
 * The [createMdc3Theme] call (a non-trivial XML TypedArray fetch) is wrapped in [remember]
 * keyed on (activeTheme, isDark, context); without that key it would fire on every
 * recomposition triggered anywhere in the tree.
 */
@Composable
fun YokaiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { Injekt.get<PreferencesHelper>() }
    val isDark = isSystemInDarkTheme()

    // Reactive theme read: collectAsState subscribes to the pref's flow, caches the value,
    // and only triggers recomposition when it actually changes. Switching `if (isDark)` between
    // calls is safe because each call site keys its own remember inside collectAsState.
    val activeTheme by if (isDark) {
        preferences.darkTheme().collectAsState()
    } else {
        preferences.lightTheme().collectAsState()
    }

    val colorScheme: ColorScheme = remember(activeTheme, isDark, context) {
        // Mdc3Theme is a destructurable data class; first component is the ColorScheme.
        val (cs) =
            @Suppress("DEPRECATION")
            createMdc3Theme(
                context = context,
                layoutDirection = LayoutDirection.Rtl,
                setTextColors = true,
                readTypography = false,
            )
        // Fallback covers the (rare) case where createMdc3Theme returns null for some
        // theme attrs missing on this configuration; an empty M3 scheme keeps the app
        // booting rather than crashing the whole Compose tree.
        cs ?: lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
