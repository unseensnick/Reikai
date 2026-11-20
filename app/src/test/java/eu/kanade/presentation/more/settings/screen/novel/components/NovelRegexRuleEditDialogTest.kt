package eu.kanade.presentation.more.settings.screen.novel.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import eu.kanade.presentation.more.settings.screen.novel.NovelRegexPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.novel.content.NovelRegexReplacement
import reikai.presentation.ComposeTestHost

/**
 * Save trusts only the answer to the edit on screen: the preview arrives after typing settles, so an earlier
 * edit's success would otherwise enable Save on a pattern that does not compile, which the reader then skips.
 */
@RunWith(RobolectricTestRunner::class)
class NovelRegexRuleEditDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val rule =
        NovelRegexReplacement(title = "Rule", pattern = "[a", replacement = "b", isRegex = true, id = "r")

    private fun show(preview: NovelRegexPreview) = compose.setContent {
        ComposeTestHost {
            NovelRegexRuleEditDialog(
                rule = rule,
                preview = preview,
                onDismissRequest = {},
                onSave = {},
                onPreview = { _, _ -> },
            )
        }
    }

    @Test
    fun `an earlier edit's success leaves Save off`() {
        show(NovelRegexPreview(rule.copy(title = "", pattern = "a"), "", Result.success("b")))

        compose.onNodeWithText(SAVE).assertIsNotEnabled()
    }

    @Test
    fun `a success for the edit on screen turns Save on`() {
        show(NovelRegexPreview(rule.copy(title = ""), "", Result.success("b")))

        compose.onNodeWithText(SAVE).assertIsEnabled()
    }

    /** A rotation or fold recreates the Activity while the model keeps the dialog open. */
    @Test
    fun `what was typed survives the screen being recreated`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ComposeTestHost {
                NovelRegexRuleEditDialog(
                    rule = null,
                    preview = null,
                    onDismissRequest = {},
                    onSave = {},
                    onPreview = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Name").performTextInput("Typed")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Typed").assertIsDisplayed()
    }

    private companion object {
        const val SAVE = "Save"
    }
}
