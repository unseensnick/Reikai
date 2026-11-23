package reikai.presentation.details

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.source.SourceKey
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.DownloadProgress

/** The Start/Resume button resumes, and a tap on the same chapter's row is an open of that chapter. */
@RunWith(RobolectricTestRunner::class)
class DetailsResumeTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<Long>()
    private val resumed = mutableListOf<Long>()

    private fun show() {
        val state = MutableStateFlow(
            loadedDetails(items = listOf(detailsChapter(1L, "Chapter One", 1.0))).copy(resumeChapterId = 1L),
        )
        val behavior = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
            fake.servesThroughVolumeViews(state, noVolumePicking())
            every { fake.downloadProgress(any()) } returns DownloadProgress.Unsupported
            every { fake.duplicateMarking } returns noDuplicateMarking()
        }
        val nav = EntryDetailsNavigation(
            navigateUp = {},
            onOpenChapter = { opened += it },
            onResume = { resumed += it },
            viewedSource = DetailsViewedSource(SourceKey.Novel("src"), browsable = true),
            onOpenFilterSettings = {},
        )
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior, nav = nav).Content() } }
    }

    @Test
    fun `the Start button resumes rather than opening the chapter`() {
        show()

        compose.onNodeWithText("Start", useUnmergedTree = true).performClick()

        (resumed to opened) shouldBe (listOf(1L) to emptyList<Long>())
    }

    @Test
    fun `a tap on the resume chapter's row opens it`() {
        show()

        compose.onNodeWithText("Chapter One").performClick()

        (opened to resumed) shouldBe (listOf(1L) to emptyList<Long>())
    }
}
