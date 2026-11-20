package reikai.presentation.settings.appearance

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.util.LauncherIcon

/** The icon tiles are radio buttons, so a screen reader hears which icon is the one shown. */
@RunWith(RobolectricTestRunner::class)
class IconTileTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `only the icon shown reads as selected`() {
        compose.setContent {
            ComposeTestHost {
                Row { LauncherIcon.entries.take(2).forEachIndexed { i, icon -> IconTile(icon, isShown = i == 0) {} } }
            }
        }

        compose.onAllNodes(isSelected()).fetchSemanticsNodes().size shouldBe 1
    }
}
