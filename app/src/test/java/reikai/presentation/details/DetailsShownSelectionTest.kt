package reikai.presentation.details

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.entry.EntryId
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.DownloadTargets
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.UndatedChapterDate
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The details list's own filters narrow the rows below the model's list, so the selection verbs must
 * act on the rows on screen. Drawn over a behaviour that selects exactly what it is handed, as both
 * models do: the toolbar's count is then what the screen passed.
 */
@RunWith(RobolectricTestRunner::class)
class DetailsShownSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = MutableStateFlow(loaded())

    private val behavior = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
        every { fake.state } returns state
        every { fake.downloadProgress } returns EntryDownloadProgress.Unsupported
        every { fake.duplicateMarking } returns noDuplicateMarking()
        every { fake.volumePicking } returns noVolumePicking()
        every { fake.toggleSelection(any(), any(), any()) } answers {
            select(setOf(firstArg<Long>()))
        }
        every { fake.selectAll(any()) } answers { select(firstArg<List<Long>>().toSet()) }
    }

    @Test
    fun `select all under Only copies selects the copies alone`() {
        compose.setContent { ComposeTestHost { Navigator(DetailsContentHost(behavior)) } }

        compose.onNodeWithText("Show only these", substring = true).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Copy one"))
        compose.onNodeWithText("Copy one").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select all").performClick()

        compose.onNodeWithText(COPIES.size.toString()).assertExists()
    }

    private fun select(ids: Set<Long>) = state.update { it.copy(selection = it.selection + ids) }

    private companion object {
        val COPIES = listOf(1L, 2L)

        fun loaded() = loadedDetails(
            items = listOf(
                detailsChapter(1L, "Copy one", 1.0),
                detailsChapter(2L, "Copy two", 1.0),
                detailsChapter(3L, "Another", 2.0),
                detailsChapter(4L, "And another", 3.0),
            ),
            duplicates = DuplicateMarks(copies = COPIES.associateWith { 2 }, marks = emptyMap()),
        )
    }
}
