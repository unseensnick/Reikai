package reikai.presentation.reader.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.materialkolor.PaletteStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.CustomColorScheme
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.components.toHexRgb
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The web reader's chapter-end icons take the app theme's Compose colours, as the text renderer's do. Custom
 * exists only in Compose, so a View theme read would draw it in the default theme's blue.
 */
@RunWith(RobolectricTestRunner::class)
class NovelWebChromeColorsTest {

    @Test
    fun `under the Custom theme the page's primary is the user's seeded one`() {
        val seed = Color(0xFF2E7D32)
        val preferences = UiPreferences(InMemoryPreferenceStore()).apply {
            appTheme.set(AppTheme.CUSTOM)
            customThemeSeed.set(seed.toArgb())
            customThemeStyle.set(PaletteStyle.Fidelity)
        }
        val seeded = CustomColorScheme(seed, PaletteStyle.Fidelity)
            .getColorScheme(isDark = false, isAmoled = false, overrideDarkSurfaceContainers = true).primary

        NovelWebDocument.chromeVariables(ApplicationProvider.getApplicationContext(), preferences) shouldContain
            "--rk-primary:${seeded.toArgb().toHexRgb()};"
    }
}
