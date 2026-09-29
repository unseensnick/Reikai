package reikai.data.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.novel.host.ChapterItem

/** A plugin's chapter list as the sync stores it, which the migration count peek counts too. */
class NovelSourceChaptersTest {

    private fun chapters(vararg items: ChapterItem, page: String? = null) =
        items.toList().toSourceChapters(novelId = 1L, novelTitle = "Title", page = page)

    @Test
    fun `a path the plugin lists twice is one chapter`() {
        chapters(ChapterItem("One", "/a"), ChapterItem("One", "/a"), ChapterItem("Two", "/b"))
            .map { it.url } shouldBe listOf("/a", "/b")
    }

    @Test
    fun `an entity in a name is decoded before its number is read`() {
        chapters(ChapterItem("Chapter&#160;12", "/a")).single().chapterNumber shouldBe 12.0
    }

    @Test
    fun `a plugin's own chapter number is trusted over the name`() {
        chapters(ChapterItem("Chapter 12", "/a", chapterNumber = 5.0)).single().chapterNumber shouldBe 5.0
    }

    @Test
    fun `a paged sync stamps its page on every chapter`() {
        chapters(ChapterItem("One", "/a", page = "v1"), page = "3").single().page shouldBe "3"
    }

    @Test
    fun `an unpaged sync keeps the plugin's own page label`() {
        chapters(ChapterItem("One", "/a", page = "v1")).single().page shouldBe "v1"
    }
}
