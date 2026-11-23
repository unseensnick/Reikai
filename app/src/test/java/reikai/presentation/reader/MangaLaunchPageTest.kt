package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/**
 * A page preview opens the chapter it was tapped in at that page, even when another source of the
 * merged series has the chapter on disk: the page is an index into the tapped chapter's own pages,
 * which the other source can number differently. Manga only, since only a gallery has page previews.
 */
class MangaLaunchPageTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a page preview opens the chapter tapped rather than another source's copy on disk`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val gallery = harness.manga(1L, source = 100L, title = "Gallery")
            val other = harness.manga(2L, source = 200L, title = "Other")
            val pooled = listOf(harness.chapter(11L, gallery, 1.0), harness.chapter(21L, other, 1.0))
            val stitch = pooled.mapIndexed { copy, chapter -> ChapterUnit(chapter.id, unit = 0, copyOrder = copy) }
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(gallery.id to gallery, other.id to other),
                chapters = pooled.take(1),
                sourceNameByMangaId = mapOf(gallery.id to "Gallery", other.id to "Other"),
                stitch = stitch,
                pooledChapters = pooled,
            )
            harness.open(gallery, chapterId = 11L, group = group, onDisk = setOf(21L), launchPage = 11) { _, state ->
                state.viewerChapters!!.currChapter.chapter.id
            } shouldBe 11L
        }
    }
}
