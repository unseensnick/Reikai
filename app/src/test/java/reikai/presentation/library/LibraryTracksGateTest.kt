package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort

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

    private fun category(sort: LibrarySort) =
        Category(id = 3L, name = "c", order = 0L, flags = sort.flag or CATEGORY_SORT_CUSTOMIZED)
}
