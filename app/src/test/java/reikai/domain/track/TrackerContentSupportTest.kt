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

    private val bothTypes = DummyTracker(id = 1L, name = "Both", supportsNovels = true, supportsManga = true)
    private val novelsOnly = DummyTracker(id = 2L, name = "Novels", supportsNovels = true, supportsManga = false)
    private val mangaOnly = DummyTracker(id = 3L, name = "Manga", supportsNovels = false, supportsManga = true)

    private val all = listOf(bothTypes, novelsOnly, mangaOnly)

    @Test
    fun `offers a novel only the trackers that catalogue novels`() {
        all.supportingContent(isNovel = true) shouldBe listOf(bothTypes, novelsOnly)
    }

    @Test
    fun `offers a manga only the trackers that catalogue manga`() {
        all.supportingContent(isNovel = false) shouldBe listOf(bothTypes, mangaOnly)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `never offers a tracker that catalogues neither type`(isNovel: Boolean) {
        val neither = DummyTracker(id = 4L, name = "Neither", supportsNovels = false, supportsManga = false)

        listOf(neither).supportingContent(isNovel) shouldBe emptyList()
    }
}
