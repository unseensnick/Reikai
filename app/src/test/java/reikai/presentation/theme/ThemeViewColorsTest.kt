package reikai.presentation.theme

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import eu.kanade.presentation.theme.colorscheme.CloudflareColorScheme
import eu.kanade.presentation.theme.colorscheme.CottoncandyColorScheme
import eu.kanade.presentation.theme.colorscheme.DokiColorScheme
import eu.kanade.presentation.theme.colorscheme.DoomColorScheme
import eu.kanade.presentation.theme.colorscheme.LimeColorScheme
import eu.kanade.presentation.theme.colorscheme.MatrixColorScheme
import eu.kanade.presentation.theme.colorscheme.MochaColorScheme
import eu.kanade.presentation.theme.colorscheme.SapphireColorScheme
import eu.kanade.presentation.theme.colorscheme.SpringAndDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberriesColorScheme
import eu.kanade.presentation.theme.colorscheme.TealAndSapphireColorScheme
import eu.kanade.tachiyomi.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.google.android.material.R.attr as M

/**
 * Each of Reikai's themes is written three times, its Compose scheme and the day and night colour files its View
 * theme reads, with nothing else binding them. A View-drawn screen (a settings dialog, the reader's pages) then
 * shows a different palette from the Compose one, so each role a View theme sets is held to the Compose scheme.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ThemeViewColorsTest(private val case: Case) {

    @Test
    fun `the View theme draws each role in the Compose scheme's colour`() {
        RuntimeEnvironment.setQualifiers(case.qualifier)
        val themed = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), case.style)

        ROLES.filter { (_, role) ->
            themed.color(role.attr) != role.of(case.expected).toArgb()
        }.map { it.first } shouldBe
            emptyList()
    }

    private fun Context.color(attr: Int): Int? {
        val read = obtainStyledAttributes(intArrayOf(attr))
        return try {
            if (read.hasValue(0)) read.getColor(0, 0) else null
        } finally {
            read.recycle()
        }
    }

    class Role(val attr: Int, val of: (ColorScheme) -> androidx.compose.ui.graphics.Color)

    class Case(private val name: String, val style: Int, val expected: ColorScheme, val qualifier: String) {
        override fun toString() = "$name $qualifier"
    }

    companion object {

        private val ROLES = listOf(
            "primary" to Role(androidx.appcompat.R.attr.colorPrimary) { it.primary },
            "onPrimary" to Role(M.colorOnPrimary) { it.onPrimary },
            "primaryContainer" to Role(M.colorPrimaryContainer) { it.primaryContainer },
            "onPrimaryContainer" to Role(M.colorOnPrimaryContainer) { it.onPrimaryContainer },
            "secondary" to Role(M.colorSecondary) { it.secondary },
            "onSecondary" to Role(M.colorOnSecondary) { it.onSecondary },
            "secondaryContainer" to Role(M.colorSecondaryContainer) { it.secondaryContainer },
            "onSecondaryContainer" to Role(M.colorOnSecondaryContainer) { it.onSecondaryContainer },
            "tertiary" to Role(M.colorTertiary) { it.tertiary },
            "onTertiary" to Role(M.colorOnTertiary) { it.onTertiary },
            "tertiaryContainer" to Role(M.colorTertiaryContainer) { it.tertiaryContainer },
            "onTertiaryContainer" to Role(M.colorOnTertiaryContainer) { it.onTertiaryContainer },
            "background" to Role(android.R.attr.colorBackground) { it.background },
            "onBackground" to Role(M.colorOnBackground) { it.onBackground },
            "surface" to Role(M.colorSurface) { it.surface },
            "onSurface" to Role(M.colorOnSurface) { it.onSurface },
            "surfaceVariant" to Role(M.colorSurfaceVariant) { it.surfaceVariant },
            "onSurfaceVariant" to Role(M.colorOnSurfaceVariant) { it.onSurfaceVariant },
            "outline" to Role(M.colorOutline) { it.outline },
        )

        private val THEMES = listOf(
            Triple("Cotton candy", R.style.Theme_Tachiyomi_CottonCandy, CottoncandyColorScheme),
            Triple("Mocha", R.style.Theme_Tachiyomi_Mocha, MochaColorScheme),
            Triple("Cloudflare", R.style.Theme_Tachiyomi_Cloudflare, CloudflareColorScheme),
            Triple("Doom", R.style.Theme_Tachiyomi_Doom, DoomColorScheme),
            Triple("Matrix", R.style.Theme_Tachiyomi_Matrix, MatrixColorScheme),
            Triple("Sapphire", R.style.Theme_Tachiyomi_Sapphire, SapphireColorScheme),
            Triple("Spring and dusk", R.style.Theme_Tachiyomi_SpringAndDusk, SpringAndDuskColorScheme),
            Triple("Strawberries", R.style.Theme_Tachiyomi_Strawberries, StrawberriesColorScheme),
            Triple("Teal and sapphire", R.style.Theme_Tachiyomi_TealAndSapphire, TealAndSapphireColorScheme),
            Triple("Lime", R.style.Theme_Tachiyomi_Lime, LimeColorScheme),
            Triple("Doki", R.style.Theme_Tachiyomi_Doki, DokiColorScheme),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = THEMES.flatMap { (name, style, scheme) ->
            listOf(Case(name, style, scheme.lightScheme, "+notnight"), Case(name, style, scheme.darkScheme, "+night"))
        }
    }
}
