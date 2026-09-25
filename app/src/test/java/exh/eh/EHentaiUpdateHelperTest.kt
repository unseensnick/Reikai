package exh.eh

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Merging a discarded gallery version's reading state into the accepted one's chapters. */
class EHentaiUpdateHelperTest {

    private fun chain(mangaId: Long, chapter: Chapter) =
        ChapterChain(Manga.create().copy(id = mangaId), listOf(chapter), emptyList())

    private val stored = Chapter.create().copy(
        id = 10L,
        mangaId = 1L,
        url = "/g/1/abc/",
        name = "v1: Gallery",
        chapterNumber = 1.0,
        sourceOrder = 0L,
    )

    private val discarded = stored.copy(id = 20L, mangaId = 2L, read = true, bookmark = true, lastPageRead = 7L)

    private fun updateFor(accepted: Chapter) =
        getChapterList(chain(1L, accepted), listOf(chain(2L, discarded)), listOf(accepted, discarded))
            .first.single { it.id == accepted.id }

    @Test
    @DisplayName("an existing chapter keeps the read state merged from a discarded version")
    fun readIsSaved() {
        updateFor(stored).read shouldBe true
    }

    @Test
    @DisplayName("an existing chapter keeps the bookmark merged from a discarded version")
    fun bookmarkIsSaved() {
        updateFor(stored).bookmark shouldBe true
    }

    @Test
    @DisplayName("an existing chapter keeps the progress merged from a discarded version")
    fun progressIsSaved() {
        updateFor(stored).lastPageRead shouldBe 7L
    }

    @Test
    @DisplayName("a merged field that already matches the stored row is not rewritten")
    fun unchangedFieldIsLeftAlone() {
        updateFor(stored.copy(read = true)).read shouldBe null
    }
}
