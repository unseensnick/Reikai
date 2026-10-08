package exh.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A built-in gallery source updates as one chapter, the gallery itself, whichever source it is. */
class GalleryMangaUpdateTest {

    private val gallery = SManga.create().apply {
        url = "/g/1"
        title = "Gallery"
    }

    @Test
    fun `a chapter fetch lists the gallery itself as chapter one`() = runTest {
        val update = singleChapterGalleryUpdate(gallery, emptyList(), fetchDetails = false, fetchChapters = true) {
            error("details not asked for")
        }
        update.chapters.map { it.url to it.chapter_number } shouldBe listOf("/g/1" to 1f)
    }

    @Test
    fun `an update asking for neither keeps what it was given`() = runTest {
        val stored = listOf(SChapter.create().apply { url = "/g/1" })
        val update = singleChapterGalleryUpdate(gallery, stored, fetchDetails = false, fetchChapters = false) {
            error("details not asked for")
        }
        (update.manga to update.chapters) shouldBe (gallery to stored)
    }
}
