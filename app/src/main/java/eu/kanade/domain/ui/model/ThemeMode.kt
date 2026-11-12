package eu.kanade.domain.ui.model

import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

// RK --> Doki has no light palette, so it runs in night mode whatever the mode setting says, as in Yōkai
fun ThemeMode.shownWith(appTheme: AppTheme): ThemeMode = if (appTheme == AppTheme.DOKI) ThemeMode.DARK else this
// RK <--

fun setAppCompatDelegateThemeMode(themeMode: ThemeMode) {
    AppCompatDelegate.setDefaultNightMode(
        when (themeMode) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        },
    )
}
