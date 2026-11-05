package reikai.presentation.local

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.ImportProblem
import reikai.data.local.ImportResult
import reikai.data.local.ImportedSeries
import reikai.domain.library.ContentType
import reikai.presentation.ComposeTestHost

/** The import screen's list draws rows that look alike, rather than crashing on them. */
@RunWith(RobolectricTestRunner::class)
class ImportListTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `two files that failed alike are both listed`() {
        val problem = ImportProblem("bad.epub", ImportProblem.Reason.Protected)

        show(LocalImportViewModel.State(problems = listOf(problem, problem)))

        compose.onAllNodesWithText("bad.epub").fetchSemanticsNodes().size shouldBe 2
    }

    @Test
    fun `two series renamed into one folder are both listed`() {
        val series = listOf("a", "b").map { ImportedSeries(it, ContentType.MANGA, "Foo", "Foo", 1L) }

        show(LocalImportViewModel.State(result = ImportResult(series, emptyList())))

        compose.onAllNodesWithText("Foo").fetchSemanticsNodes().size shouldBe 2
    }

    private fun show(state: LocalImportViewModel.State) {
        compose.setContent {
            ComposeTestHost {
                ImportList(
                    padding = PaddingValues(),
                    state = state,
                    onRename = {},
                    onType = { _, _ -> },
                    onRemove = {},
                    onOpen = {},
                )
            }
        }
    }
}
