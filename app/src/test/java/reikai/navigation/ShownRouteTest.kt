package reikai.navigation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.presentation.TestRootRoute
import reikai.presentation.details.detailsRoute
import reikai.presentation.testNavigator
import reikai.presentation.track.EntryTrackSheetRoute

/** The page the user is on is the details page under the track sheet, so incognito and assist read that one. */
class ShownRouteTest {

    private val details = EntryId.Novel(1L).detailsRoute(fromSource = true)

    @Test
    fun `the page under the track sheet is the one shown`() {
        testNavigator(TestRootRoute, details, EntryTrackSheetRoute(EntryId.Novel(1L))).shownRoute shouldBe
            details
    }

    @Test
    fun `with no sheet open the top page is the one shown`() {
        testNavigator(TestRootRoute, details).shownRoute shouldBe details
    }
}
