package reikai.novel.local.epub

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class EpubChaptersTest {

    // Enough words that a chapter is not read as a part title and joined to the next.
    private val fill = "lorem ".repeat(30).trim()

    private fun List<Pair<String, String>>.withoutFill() = map { (title, text) ->
        title to text.replace(fill, "").replace(Regex("""\s+"""), " ").trim()
    }

    /** Three chapters in one file, each opened by an anchor at the start of its heading. */
    private val oneFile = epub(
        mapOf(
            "OEBPS/content.opf" to opf(listOf("a" to "text/all.xhtml"), nav = "nav.xhtml"),
            "OEBPS/nav.xhtml" to nav(
                "One" to "text/all.xhtml#c1",
                "Two" to "text/all.xhtml#c2",
                "Three" to "text/all.xhtml#c3",
            ),
            "OEBPS/text/all.xhtml" to xhtml(
                """<div><h2><a id="c1"/>CHAPTER ONE</h2><p>first $fill</p></div>
                   <div><h2><a id="c2"/>CHAPTER TWO</h2><p>second $fill</p></div>
                   <div><h2><a id="c3"/>CHAPTER THREE</h2><p>third $fill</p></div>""",
            ),
        ),
    )

    @Test
    fun `a file holding several chapters is cut at their anchors`() {
        chapterTexts(oneFile).withoutFill().map { it.second } shouldBe listOf(
            "CHAPTER ONE first",
            "CHAPTER TWO second",
            "CHAPTER THREE third",
        )
    }

    @Test
    fun `an anchor partway into a heading cuts the heading there`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(listOf("a" to "all.xhtml"), nav = "nav.xhtml"),
                "OEBPS/nav.xhtml" to nav("One" to "all.xhtml#a1", "Two" to "all.xhtml#a2"),
                "OEBPS/all.xhtml" to
                    xhtml("""<p id="a1">one $fill</p><h2><span>Part </span><a id="a2"/>two</h2><p>body $fill</p>"""),
            ),
        )

        chapterTexts(book).withoutFill().map { it.second } shouldBe listOf("one Part", "two body")
    }

    @Test
    fun `an anchor that opens its heading takes the whole heading`() {
        chapterTexts(oneFile).withoutFill()[1].second shouldBe "CHAPTER TWO second"
    }

    @Test
    fun `the next chapter's heading leaves no empty shell behind in the one before`() {
        val book = EpubBook.parse(oneFile)
        val html = EpubChapters.render(book, oneFile, EpubChapters.list(book, oneFile), 0) { _, _ -> null }

        Regex("<h2").findAll(html).count() shouldBe 1
    }

    @Test
    fun `a file the contents skip continues the chapter before it`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(
                    listOf("a" to "a.xhtml", "b" to "b.xhtml", "c" to "c.xhtml"),
                    nav = "nav.xhtml",
                ),
                "OEBPS/nav.xhtml" to nav("One" to "a.xhtml", "Two" to "c.xhtml"),
                "OEBPS/a.xhtml" to xhtml("<p>start $fill</p>"),
                "OEBPS/b.xhtml" to xhtml("<p>continued</p>"),
                "OEBPS/c.xhtml" to xhtml("<p>next $fill</p>"),
            ),
        )

        chapterTexts(book).withoutFill() shouldBe listOf("One" to "start continued", "Two" to "next")
    }

    @Test
    fun `what comes before the first entry opens the first chapter`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(listOf("cover" to "cover.xhtml", "a" to "a.xhtml"), nav = "nav.xhtml"),
                "OEBPS/nav.xhtml" to nav("One" to "a.xhtml"),
                "OEBPS/cover.xhtml" to xhtml("<p>title page</p>"),
                "OEBPS/a.xhtml" to xhtml("<p>one $fill</p>"),
            ),
        )

        chapterTexts(book).withoutFill() shouldBe listOf("One" to "title page one")
    }

    @Test
    fun `a part title with no text of its own joins the chapter after it`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(listOf("p" to "part.xhtml", "a" to "a.xhtml"), nav = "nav.xhtml"),
                "OEBPS/nav.xhtml" to nav("BOOK ONE" to "part.xhtml", "CHAPTER I" to "a.xhtml"),
                "OEBPS/part.xhtml" to xhtml("<h1>BOOK ONE</h1>"),
                "OEBPS/a.xhtml" to xhtml("<p>$fill</p>"),
            ),
        )

        EpubChapters.list(EpubBook.parse(book), book).map { it.title } shouldBe listOf("BOOK ONE - CHAPTER I")
    }

    @Test
    fun `a caption in front of a chapter name is dropped from the title`() {
        EpubChapters.cleanTitle("He rode a black horse. CHAPTER III.") shouldBe "CHAPTER III."
    }

    @Test
    fun `a title that only mentions a chapter keeps its words`() {
        EpubChapters.cleanTitle("The Last Chapter") shouldBe "The Last Chapter"
    }

    @Test
    fun `a book with no contents gets a chapter per file named by its heading`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml", "b" to "b.xhtml")),
                "OEBPS/a.xhtml" to xhtml("<h1>Opening</h1><p>$fill</p>"),
                "OEBPS/b.xhtml" to xhtml("<h1>Closing</h1><p>$fill</p>"),
            ),
        )

        EpubChapters.list(EpubBook.parse(book), book).map { it.title } shouldBe listOf("Opening", "Closing")
    }

    @Test
    fun `a single file with no contents is cut at its headings`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml")),
                "OEBPS/a.xhtml" to xhtml("<h2>First</h2><p>one $fill</p><h2>Second</h2><p>two $fill</p>"),
            ),
        )

        chapterTexts(book).withoutFill() shouldBe listOf("First" to "First one", "Second" to "Second two")
    }

    private val illustrated = epub(
        mapOf(
            "OEBPS/content.opf" to opf(listOf("a" to "text/a.xhtml"), nav = "nav.xhtml"),
            "OEBPS/nav.xhtml" to nav("One" to "text/a.xhtml"),
            "OEBPS/text/a.xhtml" to xhtml(
                """<svg xmlns:xlink="http://www.w3.org/1999/xlink"><image xlink:href="../images/cover%20art.jpg"/></svg>
                   <p onclick="steal()" style="color: red; text-indent: 1em">words <a href="a.xhtml#n1">1</a></p>
                   <p id="n1">note</p><script>steal()</script>""",
                head = """<link rel="stylesheet" href="../css/book.css"/>""",
            ),
            "OEBPS/css/book.css" to "p { color: #000; text-indent: 2em; } @font-face { src: url(f.ttf); }",
        ),
        binary = mapOf("OEBPS/images/cover art.jpg" to byteArrayOf(1, 2, 3)),
    )

    private fun renderIllustrated(): String {
        val book = EpubBook.parse(illustrated)
        return EpubChapters.render(book, illustrated, EpubChapters.list(book, illustrated), 0) { path, _ ->
            "data:$path"
        }
    }

    @Test
    fun `a picture framed in SVG becomes a picture, inlined from its file`() {
        renderIllustrated() shouldContain """src="data:OEBPS/images/cover art.jpg""""
    }

    @Test
    fun `nothing in a chapter can run`() {
        listOf("script", "onclick").filter { it in renderIllustrated() } shouldBe emptyList()
    }

    @Test
    fun `the book's colours give way to the reader's theme while its layout stays`() {
        val html = renderIllustrated()
        listOf("color", "#000", "font-face").filter { it in html } to ("text-indent" in html) shouldBe
            (emptyList<String>() to true)
    }

    @Test
    fun `a link to a note in the same chapter leads to it`() {
        renderIllustrated() shouldContain """href="#n1""""
    }

    @Test
    fun `a link to a file outside the chapter is dropped`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml", "b" to "b.xhtml"), nav = "nav.xhtml"),
                "OEBPS/nav.xhtml" to nav("One" to "a.xhtml", "Two" to "b.xhtml"),
                "OEBPS/a.xhtml" to xhtml("""<p><a href="b.xhtml#x">elsewhere</a> ${"text ".repeat(30)}</p>"""),
                "OEBPS/b.xhtml" to xhtml("<p id=\"x\">${"more ".repeat(30)}</p>"),
            ),
        )
        val parsed = EpubBook.parse(book)

        EpubChapters.render(parsed, book, EpubChapters.list(parsed, book), 0) { _, _ -> null } shouldNotContain "href"
    }
}
