package reikai.domain.track

import eu.kanade.test.DummyTracker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * One conformance test over both content types, so the "offer a tracker only where its catalogue
 * holds that type" rule cannot hold on one side and drift on the other.
 *
 * The case this exists for: a light-novel service was offered on the manga tracking sheet, where its
 * search answered with light novels and bound one to a manga.
 */
class TrackerContentSupportTest {

    private val novelsOnly = DummyTracker(id = 2L, name = "Novels", supportsNovels = true, supportsManga = false)
    private val mangaOnly = DummyTracker(id = 3L, name = "Manga", supportsNovels = false, supportsManga = true)

    @Test
    fun `a novels-only tracker supports novels`() {
        novelsOnly.supportsContent(isNovel = true) shouldBe true
    }

    @Test
    fun `a novels-only tracker does not support manga`() {
        novelsOnly.supportsContent(isNovel = false) shouldBe false
    }

    @Test
    fun `a manga-only tracker does not support novels`() {
        mangaOnly.supportsContent(isNovel = true) shouldBe false
    }

    @Test
    fun `a manga-only tracker supports manga`() {
        mangaOnly.supportsContent(isNovel = false) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `never offers a tracker that catalogues neither type`(isNovel: Boolean) {
        val neither = DummyTracker(id = 4L, name = "Neither", supportsNovels = false, supportsManga = false)

        neither.supportsContent(isNovel) shouldBe false
    }
}
