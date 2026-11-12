package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.MoreVert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import reikai.presentation.ComposeTestHost

/**
 * A toolbar menu opens from the button that shows it, not from the start of the actions row: the row is wider
 * than the menu here and starts well left of the buttons, so a menu anchored to the row opens far from them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h600dp-mdpi")
class AppBarActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(actions: List<AppBar.AppBarAction>) = compose.setContent {
        ComposeTestHost {
            Row(Modifier.width(700.dp)) {
                Spacer(Modifier.width(200.dp))
                AppBarActions(actions)
            }
        }
    }

    /** How far the menu item [item] opens from the left edge of the button [button] that shows it. */
    private fun offset(button: String, item: String): Float {
        compose.waitForIdle()
        val from = compose.onNodeWithContentDescription(button).fetchSemanticsNode().positionOnScreen.x
        return compose.onNodeWithText(item).fetchSemanticsNode().positionOnScreen.x - from
    }

    @Test
    fun `the overflow menu opens from the overflow button`() {
        show(icons + AppBar.OverflowAction("Settings", onClick = {}))

        compose.onNodeWithContentDescription(OVERFLOW).performClick()

        offset(OVERFLOW, "Settings") shouldBe (0f plusOrMinus SLACK)
    }

    @Test
    fun `an icon's own menu opens from that icon`() {
        val withMenu = AppBar.Action("Download", MaterialSymbols.Rounded.MoreVert, onClick = {}) {
            DropdownMenu(expanded = true, onDismissRequest = {}) {
                DropdownMenuItem(text = { Text("Next chapter") }, onClick = {})
            }
        }

        show(icons + withMenu)

        offset("Download", "Next chapter") shouldBe (0f plusOrMinus SLACK)
    }

    private companion object {
        const val OVERFLOW = "More options"

        // The item's text sits inside the menu's padding and the icon inside its button, a few pixels apart.
        const val SLACK = 24f

        val icons = List(4) { AppBar.Action("Icon $it", MaterialSymbols.Rounded.MoreVert, onClick = {}) }
    }
}
