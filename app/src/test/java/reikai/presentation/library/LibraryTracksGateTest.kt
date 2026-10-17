package reikai.presentation.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/** Both libraries read tracks only while a filter, a sort or the grouping uses them. */
class LibraryTracksGateTest {

    private val alphabetical = LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending)
    private val trackerMean = LibrarySort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Ascending)

    @Test
    fun `nothing that reads tracks leaves them unloaded`() {
        libraryNeedsTracks(
            listOf(TriState.DISABLED),
            alphabetical,
            listOf(category(alphabetical)),
            LibraryGroup.BY_DEFAULT,
        ) shouldBe
            false
    }

    @Test
    fun `a tracker filter loads them`() {
        libraryNeedsTracks(listOf(TriState.ENABLED_NOT), alphabetical, emptyList(), LibraryGroup.BY_DEFAULT) shouldBe
            true
    }

    @Test
    fun `the tracker score as the library sort loads them`() {
        libraryNeedsTracks(emptyList(), trackerMean, emptyList(), LibraryGroup.BY_DEFAULT) shouldBe true
    }

    @Test
    fun `a category sorted by tracker score loads them`() {
        libraryNeedsTracks(emptyList(), alphabetical, listOf(category(trackerMean)), LibraryGroup.BY_DEFAULT) shouldBe
            true
    }

    @Test
    fun `grouping by tracking status loads them`() {
        libraryNeedsTracks(emptyList(), alphabetical, emptyList(), LibraryGroup.BY_TRACK_STATUS) shouldBe true
    }

    /** Filtering a tracker query against no tracks would show an empty library while they load. */
    @Test
    fun `a search that reads tracks waits until they have loaded`() = runTest {
        val query = MutableStateFlow<String?>(null)
        val seen = mutableListOf<Pair<String?, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gate(query) {
                flow {
                    delay(1_000L)
                    emit(mapOf(1L to listOf("track")))
                }
            }
                .collect { (search, tracks) -> seen += search to tracks.size }
        }

        query.value = "tracker:AniList"
        advanceTimeBy(2_000L)
        runCurrent()

        seen shouldBe listOf(null to 0, "tracker:AniList" to 1)
    }

    private fun gate(query: MutableStateFlow<String?>, tracks: () -> Flow<Map<Long, List<String>>>) =
        libraryTracksFlow(
            filters = flowOf(filterSettings),
            categories = flowOf(emptyList()),
            libraryPreferences = LibraryPreferences(EmittingPreferenceStore()),
            reikaiLibraryPreferences = ReikaiLibraryPreferences(EmittingPreferenceStore()),
            search = query,
            queryOf = { it },
            tracks = tracks,
        )

    private val filterSettings = LibraryFilterSettings(
        downloadedOnly = false,
        downloaded = TriState.DISABLED,
        unread = TriState.DISABLED,
        started = TriState.DISABLED,
        bookmarked = TriState.DISABLED,
        completed = TriState.DISABLED,
        intervalCustom = TriState.DISABLED,
        skipsOutsideReleasePeriod = true,
        lewd = TriState.DISABLED,
        trackers = emptyMap(),
        categoriesEnabled = false,
        categoriesInclude = emptySet(),
        categoriesExclude = emptySet(),
    )

    private fun category(sort: LibrarySort) =
        Category(id = 3L, name = "c", order = 0L, flags = sort.flag or CATEGORY_SORT_CUSTOMIZED)
}
