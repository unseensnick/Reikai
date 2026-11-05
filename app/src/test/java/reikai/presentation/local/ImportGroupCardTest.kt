package reikai.presentation.local

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
import reikai.data.local.ImportGroup
import reikai.domain.library.ContentType
import reikai.presentation.ComposeTestHost

/** An import card on a phone at the largest font size keeps both type chips readable. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportGroupCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `at the largest font size the type chips keep one line each`() {
        compose.setContent {
            ComposeTestHost {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(Modifier.width(360.dp)) {
                        GroupCard(group = GROUP, enabled = true, onRename = {}, onType = {}, onRemove = {})
                    }
                }
            }
        }

        listOf(lines("Novels"), lines("Manga")) shouldBe listOf(1, 1)
    }

    private fun lines(label: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(
            label,
        ).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    private companion object {
        val GROUP = ImportGroup(
            key = "divine",
            type = ContentType.NOVELS,
            title = "Divine Emperor of Death",
            author = "Stardust_breaker",
            cover = null,
            files = emptyList(),
            canBeEitherType = true,
        )
    }
}
