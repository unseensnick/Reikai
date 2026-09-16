package reikai.data.legacy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibrarySort

/**
 * The legacy importer builds a synthetic backup that is restored at first launch, and a novel category's
 * flags come out of the old database in the pre-unification layout. Translating them anywhere later is
 * not possible: once a value is in the shared table it is equally consistent with an untranslated old
 * one and a correct current one.
 */
class LegacyNovelCategoryFlagsTest {

    private val legacyDownloaded = 0b100000L
    private val legacyTrackerMean = 0b100100L

    @Test
    fun `a legacy Downloaded sort does not come back as Tracker score`() {
        val category = LegacyYokaiDbImporter.legacyNovelCategory("Reading", 0, legacyDownloaded)

        LibrarySort.Type.valueOf(category.flags) shouldBe LibrarySort.Type.Downloaded
    }

    @Test
    fun `a legacy Tracker score sort does not come back as Downloaded`() {
        val category = LegacyYokaiDbImporter.legacyNovelCategory("Reading", 0, legacyTrackerMean)

        LibrarySort.Type.valueOf(category.flags) shouldBe LibrarySort.Type.TrackerMean
    }

    @Test
    fun `the name and order pass through untouched`() {
        val category = LegacyYokaiDbImporter.legacyNovelCategory("Finished", 4, legacyDownloaded)

        category.name shouldBe "Finished"
        category.order shouldBe 4
    }
}
