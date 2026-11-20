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
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.DownloadTargets
import reikai.domain.source.SourceKey
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.UndatedChapterDate
import reikai.presentation.components.VolumeMoves
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/** The details content drawn over [behavior], as a details screen draws it, with [snackbarHostState] to read. */
internal class DetailsContentHost(
    private val behavior: EntryDetailsBehavior,
    private val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    // Built with the host rather than the composition, as a screen keeps it with its model.
    private val volumeMoves: VolumeMoves = VolumeMoves(CoroutineScope(Dispatchers.Unconfined), snackbarHostState),
) {
    @Composable
    fun Content() {
        val loaded by behavior.state.collectAsState()
        val store = InMemoryPreferenceStore()
        EntryDetailsContent(
            behavior = behavior,
            state = loaded as EntryDetailsScreenState.Loaded,
            snackbarHostState = snackbarHostState,
            isTabletUi = false,
            chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
            chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
            nav = EntryDetailsNavigation(
                navigateUp = {},
                onOpenChapter = {},
                viewedSource = DetailsViewedSource(SourceKey.Manga(1L), browsable = true),
                onOpenFilterSettings = {},
            ),
            settings = DetailsSettings(
                UiPreferences(store),
                ReikaiLibraryPreferences(store),
                CoroutineScope(Dispatchers.Unconfined),
            ),
            volumeMoves = volumeMoves,
        )
    }
}

/**
 * Serves [state] the way an adapter does, through the volume views kernel over [picking] with its Only copies
 * switch, so a page drawn over this mocked behaviour groups and filters its rows as a real one's.
 */
internal fun EntryDetailsBehavior.servesThroughVolumeViews(
    state: StateFlow<EntryDetailsScreenState>,
    picking: VolumePicking,
) {
    val inputs = DetailsVolumeInputs(picking, ContentType.MANGA)
    val behavior = this
    every { behavior.volumePicking } returns picking
    every { behavior.state } returns combine(state, inputs.values, EntryDetailsScreenState::withVolumeViews)
        .stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, state.value)
    every { behavior.setOnlyCopies(any()) } answers { inputs.setOnlyCopies(firstArg()) }
}

/** A manga chapter row for a details test. */
internal fun detailsChapter(id: Long, name: String, number: Double) = EntryChapterListItem.Chapter(
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

/** A loaded manga details state holding [items], for a test that draws the details content. */
internal fun loadedDetails(
    items: List<EntryChapterListItem> = emptyList(),
    duplicates: DuplicateMarks = DuplicateMarks(copies = emptyMap(), marks = emptyMap()),
) = EntryDetailsScreenState.Loaded(
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
        items = items,
        missingChapterCount = 0,
        showHidden = false,
        hasHiddenChapters = false,
        hiddenChapterIds = emptySet(),
        // The dated line reads the date format off the app graph, which a test host has none of.
        undatedChapterDate = UndatedChapterDate.Blank,
        duplicates = duplicates,
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
