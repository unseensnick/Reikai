package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter

class NovelHiddenChaptersTest {

    private fun chapter(novelId: Long) = NovelChapter(
        id = 1L, novelId = novelId, url = "/c1", name = "c1", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = 0L, dateUpload = 0L, page = "",
    )

    @Test
    fun `a chapter is keyed by the source of the novel that owns it`() {
        chapter(novelId = 2L).hiddenKey(mapOf(1L to "anchor", 2L to "sibling")) shouldBe "sibling|/c1"
    }

    @Test
    fun `a chapter whose owner is unknown has no key`() {
        chapter(novelId = 3L).hiddenKey(mapOf(1L to "anchor")) shouldBe null
    }
}
