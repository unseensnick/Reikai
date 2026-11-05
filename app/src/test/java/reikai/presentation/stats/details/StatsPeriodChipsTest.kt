package reikai.presentation.stats.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import reikai.presentation.ComposeTestHost
import reikai.presentation.stats.ReadingPeriod

/** The Reading time period chips on a narrow phone at the largest font size keep every label readable. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsPeriodChipsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `at the largest font size every period chip keeps one line`() {
        compose.setContent {
            ComposeTestHost {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.width(360.dp)) {
                        PeriodChips(selected = ReadingPeriod.WEEK, onSelect = {})
                    }
                }
            }
        }

        listOf("Week", "Month", "Year", "Custom").map(::lines) shouldBe listOf(1, 1, 1, 1)
    }

    private fun lines(label: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }
}
