package reikai.presentation.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.presentation.more.settings.widget.ShownContentPreferenceWidget
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.library.ContentType

/** The Show setting drawn in Appearance and on the theme step, and the chips it takes away. */
@RunWith(RobolectricTestRunner::class)
class ShownContentUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showChips(shown: ContentType) = compose.setContent {
        CompositionLocalProvider(LocalShownContent provides shown) {
            ContentTypeFilterChips(selected = ContentType.ALL, onSelect = {})
        }
    }

    private fun showBadge(shown: ContentType) = compose.setContent {
        CompositionLocalProvider(LocalShownContent provides shown) {
            ContentTypeBadge(ContentType.NOVELS)
        }
    }

    @Test
    fun `the setting offers all three choices and marks the current one`() {
        compose.setContent { ShownContentPreferenceWidget(value = ContentType.MANGA, onItemClick = {}) }

        compose.onNodeWithText("Manga and novels").assertExists()
        compose.onNodeWithText("Novels only").assertExists()
        compose.onNodeWithText("Manga only").assertIsOn()
    }

    @Test
    fun `picking a choice reports it`() {
        var picked: ContentType? = null
        compose.setContent { ShownContentPreferenceWidget(value = ContentType.ALL, onItemClick = { picked = it }) }

        compose.onNodeWithText("Novels only").performClick()

        picked shouldBe ContentType.NOVELS
    }

    @Test
    fun `the type chips show while both types do`() {
        showChips(ContentType.ALL)

        compose.onNodeWithText("Manga").assertExists()
    }

    @Test
    fun `the type chips leave with a hidden type`() {
        showChips(ContentType.NOVELS)

        compose.onNodeWithText("Novels").assertDoesNotExist()
    }

    @Test
    fun `a row's type badge leaves with a hidden type`() {
        showBadge(ContentType.NOVELS)

        compose.onNodeWithText("Novels").assertDoesNotExist()
    }
}
