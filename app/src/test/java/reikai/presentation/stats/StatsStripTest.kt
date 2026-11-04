package reikai.presentation.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.presentation.ComposeTestHost
import tachiyomi.presentation.core.components.SectionCard

/** The overview strip in its shelf, which measures its rows by intrinsic height, as the screen draws it. */
@RunWith(RobolectricTestRunner::class)
class StatsStripTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the strip draws inside its shelf`() {
        val figures = listOf(StatsFigure(MaterialSymbols.Rounded.Book, "4,812", "Read chapters"))
        compose.setContent {
            ComposeTestHost {
                StatsShelfContent(
                    StatsShelf.GLANCE,
                    listOf<StatsCard>({ fill -> SectionCard(modifier = fill) { StatsStrip(figures) } }),
                )
            }
        }

        compose.onNodeWithText("Read chapters").assertIsDisplayed()
    }

    // One card across at any width, so the title follows the width, not the column count.
    @Test
    @Config(qualifiers = "w600dp")
    fun `a wide screen titles the one-card shelf`() {
        compose.setContent {
            ComposeTestHost {
                StatsShelfContent(StatsShelf.GLANCE, listOf<StatsCard>({ fill -> SectionCard(modifier = fill) {} }))
            }
        }

        compose.onNodeWithText("At a glance").assertIsDisplayed()
    }
}
