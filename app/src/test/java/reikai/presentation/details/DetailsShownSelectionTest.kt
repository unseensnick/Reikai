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
        compose.setContent { ComposeTestHost { Navigator(Host(behavior)) } }

        compose.onNodeWithText("Show only these", substring = true).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Copy one"))
        compose.onNodeWithText("Copy one").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select all").performClick()

        compose.onNodeWithText(COPIES.size.toString()).assertExists()
    }

    private fun select(ids: Set<Long>) = state.update { it.copy(selection = it.selection + ids) }

    private class Host(private val behavior: EntryDetailsBehavior) : Screen {
        @Composable
        override fun Content() {
            val loaded by behavior.state.collectAsState()
            val store = InMemoryPreferenceStore()
            EntryDetailsContent(
                behavior = behavior,
                state = loaded as EntryDetailsScreenState.Loaded,
                snackbarHostState = SnackbarHostState(),
                isTabletUi = false,
                chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                nav = EntryDetailsNavigation(
                    navigateUp = {},
                    onOpenChapter = {},
                    onGlobalSearch = {},
                    onTagSearch = {},
                    onOpenFilterSettings = {},
                    onOpenTrackingSettings = {},
                    onEditNotes = {},
                    onLibrarySearch = {},
                ),
                settings = DetailsSettings(
                    UiPreferences(store),
                    ReikaiLibraryPreferences(store),
                    CoroutineScope(Dispatchers.Unconfined),
                ),
            )
        }
    }

    private companion object {
        val COPIES = listOf(1L, 2L)

        fun chapter(id: Long, name: String, number: Double) = EntryChapterListItem.Chapter(
            id = id,
            name = name,
            subtitle = null,
            read = false,
            bookmark = false,
            dateUpload = null,
            chapterNumber = number,
            progress = null,
            downloadState = Download.State.NOT_DOWNLOADED,
            numberHinted = false,
        )

        fun loaded() = EntryDetailsScreenState.Loaded(
            entryId = EntryId.Manga(100L),
            viewedEntryId = 100L,
            details = EntryDetailsUiState(
                header = EntryHeaderUi(
                    coverModel = Unit,
                    title = "Series",
                    author = null,
                    artist = null,
                    status = 0L,
                    sourceName = "",
                    sourceState = EntrySourceState.Installed,
                    sourceQuery = null,
                ),
                favorite = true,
                trackingCount = 0,
                hasLoggedInTrackers = false,
                nextUpdate = null,
                isUserIntervalMode = false,
                description = null,
                tags = null,
                notes = "",
                descriptionDefaultExpanded = false,
            ),
            chapters = EntryChapterListUiState(
                items = listOf(
                    chapter(1L, "Copy one", 1.0),
                    chapter(2L, "Copy two", 1.0),
                    chapter(3L, "Another", 2.0),
                    chapter(4L, "And another", 3.0),
                ),
                missingChapterCount = 0,
                showHidden = false,
                hasHiddenChapters = false,
                hiddenChapterIds = emptySet(),
                // The dated line reads the date format off the app graph, which a test host has none of.
                undatedChapterDate = UndatedChapterDate.Blank,
                duplicates = DuplicateMarks(copies = COPIES.associateWith { 2 }, marks = emptyMap()),
            ),
            capabilities = EntryCapabilities(),
            mergeSources = emptyList(),
            selectedSourceId = null,
            hasActiveFilter = false,
            isRefreshing = false,
            selection = emptySet(),
            resumeChapterId = null,
            hasStarted = false,
            hasViewedDownloads = false,
            showChapterNumberOnly = false,
            seedColor = null,
            webPage = null,
            downloadTargets = DownloadTargets.OWN,
        )
    }
}
