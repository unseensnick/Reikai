package reikai.presentation.novel.browse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** A plugin filter's row in the novel filter sheet, for the types that draw no fixed option list. */
@RunWith(RobolectricTestRunner::class)
class NovelFilterItemTest {

    @get:Rule
    val compose = createComposeRule()

    private var sent: JsonElement? = null

    private fun show(schema: String, current: String? = null) = compose.setContent {
        ComposeTestHost {
            NovelFilterItem(
                schema = Json.parseToJsonElement(schema).jsonObject,
                current = current?.let(Json::parseToJsonElement),
                onValueChange = { sent = it },
            )
        }
    }

    @Test
    fun `a value typed into a MultiText filter is added to its list`() {
        show("""{"type":"MultiText","label":"Tags"}""", current = """["isekai"]""")

        compose.onNodeWithText("Tags").performTextInput("magic")
        compose.onNodeWithText("Tags").performImeAction()

        sent.toString() shouldBe """["isekai","magic"]"""
    }

    @Test
    fun `a filter of a type this version cannot draw shows as unsupported`() {
        show("""{"type":"Slider","label":"Rating"}""")

        compose.onNodeWithText("Not supported in this app version").assertIsDisplayed()
    }
}
