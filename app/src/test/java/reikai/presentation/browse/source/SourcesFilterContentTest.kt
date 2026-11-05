package reikai.presentation.browse.source

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.library.ContentType
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.LocalShownContent

/** Hiding novels while the screen is open moves it to the manga half, rather than keeping a hidden chip's list. */
@RunWith(RobolectricTestRunner::class)
class SourcesFilterContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `hiding novels while the Novels chip is picked shows the manga half`() {
        val shown = mutableStateOf(ContentType.ALL)
        compose.setContent {
            ComposeTestHost {
                CompositionLocalProvider(LocalShownContent provides shown.value) {
                    SourcesFilterContent(
                        initial = ContentType.NOVELS,
                        navigateUp = {},
                        mangaSources = { Text(MANGA) },
                        novelSources = { Text(NOVELS) },
                    )
                }
            }
        }
        compose.onNodeWithText(NOVELS).assertIsDisplayed()

        shown.value = ContentType.MANGA

        compose.onNodeWithText(MANGA).assertIsDisplayed()
    }

    private companion object {
        const val MANGA = "manga half"
        const val NOVELS = "novel half"
    }
}
