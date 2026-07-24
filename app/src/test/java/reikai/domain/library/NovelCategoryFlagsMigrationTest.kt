package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import tachiyomi.domain.library.model.LibrarySort

/**
 * Pins the novel-to-manga flag translation used when novel categories fold into the shared table. The
 * silent-failure risk is the two swapped type values (Downloaded and TrackerMean): a raw copy would flip
 * one sort into the other with no error. These tests assert the swap happens and nothing else moves.
 */
class NovelCategoryFlagsMigrationTest {

    // The legacy novel bit layout for the two swapped types (mirror of NovelLibrarySort, which is retired).
    private val novelDownloaded = 0b100000L
    private val novelTrackerMean = 0b100100L

    @Test
    fun `novel Downloaded translates to manga Downloaded`() {
        val translated = novelCategoryFlagsToMangaLayout(novelDownloaded)
        LibrarySort.Type.valueOf(translated) shouldBe LibrarySort.Type.Downloaded
    }

    @Test
    fun `novel TrackerMean translates to manga TrackerMean`() {
        val translated = novelCategoryFlagsToMangaLayout(novelTrackerMean)
        LibrarySort.Type.valueOf(translated) shouldBe LibrarySort.Type.TrackerMean
    }

    @Test
    fun `a type shared by both layouts passes through unchanged`() {
        val lastUpdate = LibrarySort.Type.LastUpdate.flag
        novelCategoryFlagsToMangaLayout(lastUpdate) shouldBe lastUpdate
    }

    @Test
    fun `Alphabetical (zero type bits) passes through unchanged`() {
        novelCategoryFlagsToMangaLayout(0L) shouldBe 0L
    }

    @Test
    fun `the customized, direction and hidden bits are preserved through the swap`() {
        val flags = novelDownloaded or
            CATEGORY_SORT_CUSTOMIZED or
            LibrarySort.Direction.Ascending.flag or
            CATEGORY_HIDDEN_MASK

        val translated = novelCategoryFlagsToMangaLayout(flags)

        LibrarySort.valueOf(translated) shouldBe
            LibrarySort(LibrarySort.Type.Downloaded, LibrarySort.Direction.Ascending)
        (translated and CATEGORY_SORT_CUSTOMIZED) shouldBe CATEGORY_SORT_CUSTOMIZED
        (translated and CATEGORY_HIDDEN_MASK) shouldBe CATEGORY_HIDDEN_MASK
    }
}
