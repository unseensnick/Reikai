package reikai.presentation.library

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibrarySort

class LibrarySortOptionsTest {

    private val withTracker = listOf(
        LibrarySort.Type.Alphabetical,
        LibrarySort.Type.TotalChapters,
        LibrarySort.Type.LastRead,
        LibrarySort.Type.LastUpdate,
        LibrarySort.Type.UnreadCount,
        LibrarySort.Type.LatestChapter,
        LibrarySort.Type.ChapterFetchDate,
        LibrarySort.Type.DateAdded,
        LibrarySort.Type.TrackerMean,
        LibrarySort.Type.Downloaded,
        LibrarySort.Type.Random,
    )

    @Test
    fun `with a tracker the sheet offers every sort type in Mihon's order`() {
        librarySortTypes(hasTracker = true) shouldContainExactly withTracker
    }

    @Test
    fun `without a tracker the sheet drops only the tracker score`() {
        librarySortTypes(hasTracker = false) shouldContainExactly withTracker - LibrarySort.Type.TrackerMean
    }

    @Test
    fun `the sheet misses no sort type the library can apply`() {
        librarySortTypes(hasTracker = true) shouldContainExactlyInAnyOrder LibrarySort.types
    }
}
