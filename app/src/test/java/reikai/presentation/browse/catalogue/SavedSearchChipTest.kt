package reikai.presentation.browse.catalogue

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/**
 * A saved search's chip: a tap applies it, a long press offers to delete it. The chip's own clickable
 * swallows a long press given to its modifier, so this pins that the gesture still reaches it.
 */
@RunWith(RobolectricTestRunner::class)
class SavedSearchChipTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun showChip() = compose.setContent {
        ComposeTestHost {
            SavedSearchChip(
                selected = false,
                label = LABEL,
                onClick = { calls += "apply" },
                onLongClick = { calls += "delete" },
            )
        }
    }

    @Test
    fun `a tap applies the saved search`() {
        showChip()

        compose.onNodeWithText(LABEL).performClick()

        calls shouldBe listOf("apply")
    }

    @Test
    fun `a long press offers to delete it`() {
        showChip()

        compose.onNodeWithText(LABEL).performTouchInput { longClick() }

        calls shouldBe listOf("delete")
    }

    private companion object {
        const val LABEL = "Completed romance"
    }
}
