package reikai.novel.local

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.novel.local.epub.EpubBook
import reikai.novel.local.epub.EpubChapterKey
import reikai.novel.local.epub.EpubChapters
import reikai.novel.local.epub.epub
import reikai.novel.local.epub.nav
import reikai.novel.local.epub.opf
import reikai.novel.local.epub.xhtml

/** A local chapter's pictures reach the reader whatever their size, and only from the book that holds them. */
class LocalNovelPicturesTest {

    private val illustrated = epub(
        mapOf(
            "OEBPS/content.opf" to opf(
                listOf("a" to "text/a.xhtml"),
                nav = "nav.xhtml",
                extraManifest = """<item id="p" href="images/plate.png" media-type="image/png"/>""",
            ),
            "OEBPS/nav.xhtml" to nav("One" to "text/a.xhtml"),
            "OEBPS/text/a.xhtml" to xhtml("""<p>${"words ".repeat(30)}</p><img src="../images/plate.png"/>"""),
        ),
        binary = mapOf("OEBPS/images/plate.png" to ByteArray(6 * 1024 * 1024) { 1 }),
    )

    private fun pictureSrc(): String {
        val book = EpubBook.parse(illustrated)
        val html = LocalNovelSource.renderChapter(
            book,
            illustrated,
            EpubChapters.list(book, illustrated),
            0,
            series = "Saga",
            volume = "Book.epub",
            version = 7,
        )
        return Jsoup.parse(html).selectFirst("img")?.attr("src").orEmpty()
    }

    @Test
    fun `a picture over the inline cap stays in the chapter, named by its book and entry`() {
        pictureSrc() shouldBe LocalNovelSource.pictureAddress("Saga", "Book.epub", "OEBPS/images/plate.png", 7)
    }

    @Test
    fun `a picture address reads back as its series, book and entry`() {
        val address = LocalNovelSource.pictureAddress("A::B: C", "x::y.epub", "OEBPS/a b+c.png", 1)

        LocalNovelSource.parsePictureAddress(address) shouldBe Triple("A::B: C", "x::y.epub", "OEBPS/a b+c.png")
    }

    @Test
    fun `a chapter's address is not a picture's`() {
        LocalNovelSource.parsePictureAddress(LocalNovelSource.chapterPath("Saga", "Book.epub", EpubChapterKey("a")))
            .shouldBeNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["..", ".", "", "../Other", "Saga/../..", "a\\b", "/abs"])
    fun `a series name that leaves the folder or goes deeper is refused`(name: String) {
        LocalNovelSource.isChildName(name) shouldBe false
    }

    @Test
    fun `a series name with a colon or dots inside is one of the folder's`() {
        LocalNovelSource.isChildName("Saga: One... Part 2.epub") shouldBe true
    }
}
