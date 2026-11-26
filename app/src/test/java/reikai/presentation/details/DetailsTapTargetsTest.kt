package reikai.presentation.details

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.DownloadProgress

/** The small controls inside a details row take upstream's 40dp in-row tap box. */
@RunWith(RobolectricTestRunner::class)
class DetailsTapTargetsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showRows() {
        show()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Hinted"))
    }

    private fun show() {
        val state = MutableStateFlow(
            loadedDetails(
                items = listOf(
                    detailsChapter(1L, "Hinted", 1.0).copy(numberHinted = true),
                    detailsChapter(2L, "Plain", 2.0),
                    detailsChapter(3L, "Copy", 3.0),
                    detailsChapter(4L, "Copy again", 3.0),
                ),
                duplicates = DuplicateMarks(
                    copies = listOf(3L, 4L).let { copies ->
                        copies.associateWith { copies }
                    },
                    marks = emptyMap(),
                ),
            ),
        )
        val behavior = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
            fake.servesThroughVolumeViews(state, noVolumePicking())
            every { fake.downloadProgress(any()) } returns DownloadProgress.Unsupported
            every { fake.duplicateMarking } returns noDuplicateMarking()
        }
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior).Content() } }
    }

    @Test
    fun `the number marker's tap box is upstream's in-row size`() {
        showRows()

        compose.onNodeWithContentDescription(HINT).assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun `the number marker leaves its row no taller than a row without one`() {
        showRows()

        val hintedRow = compose.onNodeWithText("Hinted").getBoundsInRoot().height
        val plainRow = compose.onNodeWithText("Plain").getBoundsInRoot().height

        hintedRow shouldBe plainRow
    }

    @Test
    fun `the duplicate copies toggle is a full tap target`() {
        show()

        compose.onNodeWithText("Show only these", substring = true).assertHeightIsAtLeast(40.dp)
    }

    private companion object {
        const val HINT = "Chapter number may be wrong"
    }
}
