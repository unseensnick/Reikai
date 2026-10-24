package reikai.presentation.details

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.merge.DownloadTargets
import reikai.presentation.components.UndatedChapterDate

/** The library-only actions and the Tracking tap, decided once by the shared body for both types. */
class EntryDetailsLibraryActionsTest {

    private var trackingSettingsOpened = false

    private val migrate = {}
    private val editInterval = {}

    private val nav = EntryDetailsNavigation(
        navigateUp = {},
        onOpenChapter = {},
        onGlobalSearch = {},
        onTagSearch = {},
        onOpenTrackingSettings = { trackingSettingsOpened = true },
        onEditNotes = {},
        onOpenFilterSettings = {},
        onMigrate = migrate,
        onEditInterval = editInterval,
        onLibrarySearch = {},
    )

    @Test
    fun `an entry outside the library offers no Migrate`() {
        nav.forState(loaded(favorite = false)).onMigrate shouldBe null
    }

    @Test
    fun `an entry outside the library offers no interval editor`() {
        nav.forState(loaded(favorite = false)).onEditInterval shouldBe null
    }

    @Test
    fun `a library entry offers Migrate`() {
        nav.forState(loaded(favorite = true)).onMigrate shouldBe migrate
    }

    @Test
    fun `a library entry offers the interval editor`() {
        nav.forState(loaded(favorite = true)).onEditInterval shouldBe editInterval
    }

    @Test
    fun `with no tracker logged in the Tracking tap opens tracker settings`() {
        trackingTap(loaded(hasLoggedInTrackers = false), mockk(relaxed = true), nav)()

        trackingSettingsOpened shouldBe true
    }

    @Test
    fun `with a tracker logged in the Tracking tap opens the track sheet`() {
        val behavior = mockk<EntryDetailsBehavior>(relaxed = true)

        trackingTap(loaded(hasLoggedInTrackers = true), behavior, nav)()

        verify { behavior.showTrackDialog() }
    }

    private fun loaded(favorite: Boolean = true, hasLoggedInTrackers: Boolean = false) =
        EntryDetailsScreenState.Loaded(
            entryId = EntryId.Manga(1L),
            viewedEntryId = 1L,
            details = EntryDetailsUiState(
                header = EntryHeaderUi(
                    coverModel = Unit,
                    title = "",
                    author = null,
                    artist = null,
                    status = 0L,
                    sourceName = "",
                    sourceState = EntrySourceState.Installed,
                    sourceQuery = null,
                ),
                favorite = favorite,
                trackingCount = 0,
                hasLoggedInTrackers = hasLoggedInTrackers,
                nextUpdate = null,
                isUserIntervalMode = false,
                description = null,
                tags = null,
                notes = "",
                descriptionDefaultExpanded = false,
            ),
            chapters = EntryChapterListUiState(
                items = emptyList(),
                missingChapterCount = 0,
                showHidden = false,
                hasHiddenChapters = false,
                hiddenChapterIds = emptySet(),
                undatedChapterDate = UndatedChapterDate.NotApplicable,
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
