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
                .collect { seen += it.search to it.tracks.size }
        }

        query.value = "tracker:AniList"
        advanceTimeBy(2_000L)
        runCurrent()

        seen shouldBe listOf(null to 0, "tracker:AniList" to 1)
    }

    /** A sort or grouping that reads no tracks changes nothing the library filters on, so it must not rebuild it. */
    @Test
    fun `a grouping change that reads no tracks sends nothing new`() = runTest {
        val seen = mutableListOf<Any>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gate(MutableStateFlow(null)) { flowOf(emptyMap()) }.collect { seen += it }
        }

        reikaiLibraryPreferences.groupLibraryBy.set(LibraryGroup.BY_SOURCE)
        runCurrent()

        seen.size shouldBe 1
    }

    /** Filtering by a tracker against no tracks would blank the library while they load. */
    @Test
    fun `a tracker filter waits until the tracks have loaded`() = runTest {
        val filters = MutableStateFlow(filterSettings)
        val seen = mutableListOf<Pair<Boolean, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gate(MutableStateFlow(null), filters) {
                flow {
                    delay(1_000L)
                    emit(mapOf(1L to listOf("track")))
                }
            }
                .collect { seen += it.filters.isActive to it.tracks.size }
        }

        filters.value = filterSettings.copy(trackers = mapOf(1L to TriState.ENABLED_IS))
        advanceTimeBy(2_000L)
        runCurrent()

        seen shouldBe listOf(false to 0, true to 1)
    }

    /** A `category:` search matches by the ids its name resolves to, so a rename has to reach it. */
    @Test
    fun `renaming a category re-resolves an active category search`() = runTest {
        val categories = MutableStateFlow(listOf(Category(id = 3L, name = "A", order = 0L, flags = 0L)))
        val seen = mutableListOf<Map<String, Set<Long>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gate(MutableStateFlow("category:B"), categories = categories) { flowOf(emptyMap()) }
                .collect { seen += it.categoryMatches }
        }

        categories.value = listOf(Category(id = 3L, name = "B", order = 0L, flags = 0L))
        runCurrent()

        seen shouldBe listOf(mapOf("B" to emptySet()), mapOf("B" to setOf(3L)))
    }

    private val reikaiLibraryPreferences = ReikaiLibraryPreferences(EmittingPreferenceStore())

    private fun gate(
        query: MutableStateFlow<String?>,
        filters: Flow<LibraryFilterSettings> = flowOf(filterSettings),
        categories: Flow<List<Category>> = flowOf(emptyList()),
        tracks: () -> Flow<Map<Long, List<String>>>,
    ) =
        libraryTracksFlow(
            filters = filters,
            categories = categories,
            libraryPreferences = LibraryPreferences(EmittingPreferenceStore()),
            reikaiLibraryPreferences = reikaiLibraryPreferences,
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
