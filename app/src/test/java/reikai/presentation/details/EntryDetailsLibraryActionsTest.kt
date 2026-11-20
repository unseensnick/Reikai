package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey

/** The library-only actions and the Tracking tap, decided once by the shared body for both types. */
class EntryDetailsLibraryActionsTest {

    private var trackingSettingsOpened = false
    private var trackSheetOpened = false

    private val migrate = {}
    private val editInterval = {}

    private val nav = DetailsPageNav(
        perType = EntryDetailsNavigation(
            navigateUp = {},
            onOpenChapter = {},
            viewedSource = DetailsViewedSource(SourceKey.Manga(1L), browsable = true),
            onOpenFilterSettings = {},
            onEditInterval = editInterval,
        ),
        shared = SharedDetailsPushes(
            onGlobalSearch = {},
            onLibrarySearch = {},
            onTagSearch = {},
            onBrowseSource = null,
            onOpenTrackingSettings = { trackingSettingsOpened = true },
            onOpenTrackSheet = { trackSheetOpened = true },
            onEditNotes = {},
            onMigrate = migrate,
        ),
        onFilterLibraryByTag = { _, _ -> },
        onCoverClick = {},
    )

    @Test
    fun `an entry outside the library offers no Migrate`() {
        nav.forState(loaded(favorite = false)).shared.onMigrate shouldBe null
    }

    @Test
    fun `an entry outside the library offers no interval editor`() {
        nav.forState(loaded(favorite = false)).perType.onEditInterval shouldBe null
    }

    @Test
    fun `a library entry offers Migrate`() {
        nav.forState(loaded(favorite = true)).shared.onMigrate shouldBe migrate
    }

    @Test
    fun `a library entry offers the interval editor`() {
        nav.forState(loaded(favorite = true)).perType.onEditInterval shouldBe editInterval
    }

    @Test
    fun `with no tracker logged in the Tracking tap opens tracker settings`() {
        trackingTap(loaded(hasLoggedInTrackers = false), nav)()

        trackingSettingsOpened shouldBe true
    }

    @Test
    fun `with a tracker logged in the Tracking tap opens the track sheet`() {
        trackingTap(loaded(hasLoggedInTrackers = true), nav)()

        trackSheetOpened shouldBe true
    }

    private fun loaded(favorite: Boolean = true, hasLoggedInTrackers: Boolean = false) = loadedDetails().let {
        it.copy(details = it.details.copy(favorite = favorite, hasLoggedInTrackers = hasLoggedInTrackers))
    }
}
