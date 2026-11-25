package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    @ParameterizedTest(name = "in library {0}")
    @ValueSource(booleans = [true, false])
    fun `only a library entry offers Migrate and the interval editor`(favorite: Boolean) {
        val shown = nav.forState(loaded(favorite = favorite))

        shown.shared.onMigrate shouldBe migrate.takeIf { favorite }
        shown.perType.onEditInterval shouldBe editInterval.takeIf { favorite }
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
