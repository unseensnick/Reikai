package reikai.presentation.migrate.flow

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

/** The manual migration search's title chips, the same for manga and novels: a tap searches that title. */
@RunWith(RobolectricTestRunner::class)
class MigrationSearchTitleChipsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tapping an alternative title searches it`() {
        var searched: String? = null
        compose.setContent {
            ComposeTestHost {
                MigrationSearchTitleChips(listOf("Demon Slayer", "Kimetsu no Yaiba")) { searched = it }
            }
        }

        compose.onNodeWithText("Kimetsu no Yaiba").performClick()

        searched shouldBe "Kimetsu no Yaiba"
    }
}
