package reikai.novel.local.epub

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EpubBookTest {

    private val ncx = """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
  <navPoint id="1"><navLabel><text>From NCX</text></navLabel><content src="a.xhtml"/></navPoint>
</navMap><pageList><pageTarget id="p1"><navLabel><text>12</text></navLabel><content src="a.xhtml#p12"/></pageTarget></pageList>
</ncx>"""

    private fun book(vararg files: Pair<String, String>) = EpubBook.parse(epub(files.toMap()))

    @Test
    fun `a library subject heading reads as its topic`() {
        subjectTopic("Russia -- History -- Alexander I, 1801-1825 -- Fiction") shouldBe "Russia"
    }

    @Test
    fun `a subject with a date range after a comma keeps the name before it`() {
        subjectTopic("Napoleonic Wars, 1800-1815 -- Campaigns -- Russia -- Fiction") shouldBe "Napoleonic Wars"
    }

    @Test
    fun `a plain subject is kept as it is`() {
        subjectTopic(" Slice of Life ") shouldBe "Slice of Life"
    }

    @Test
    fun `an archive with only the default package and pictures is an EPUB`() {
        EpubBook.looksLikeEpub(listOf("OEBPS/content.opf", "OEBPS/a.jpg")) shouldBe true
    }

    // Browse lists every local series through this read, so it must stay off the chapter files.
    @Test
    fun `reading a book's details reads none of its chapter files`() {
        val book = epub(
            mapOf(
                "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml", "b" to "b.xhtml"), nav = "nav.xhtml"),
                "OEBPS/nav.xhtml" to nav("One" to "a.xhtml", "Two" to "b.xhtml"),
                "OEBPS/a.xhtml" to xhtml("<p>one</p>"),
                "OEBPS/b.xhtml" to xhtml("<p>two</p>"),
            ),
        )
        val read = mutableSetOf<String>()

        val parsed = EpubBook.parse { paths ->
            read += paths
            book.read(paths)
        }

        read.filter { it in parsed.spine } shouldBe emptyList()
    }

    @Test
    fun `an EPUB 3 nav is read before the EPUB 2 contents`() {
        book(
            "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml"), nav = "nav.xhtml", ncx = "toc.ncx"),
            "OEBPS/nav.xhtml" to nav("From nav" to "a.xhtml"),
            "OEBPS/toc.ncx" to ncx,
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).toc.map { it.title } shouldBe listOf("From nav")
    }

    @Test
    fun `the NCX's page list is not read as chapters`() {
        book(
            "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml"), ncx = "toc.ncx"),
            "OEBPS/toc.ncx" to ncx,
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).toc.map { it.title } shouldBe listOf("From NCX")
    }

    @Test
    fun `a nested contents keeps its depth`() {
        book(
            "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml"), nav = "nav.xhtml"),
            "OEBPS/nav.xhtml" to nav("Part" to "a.xhtml#p", ">Chapter" to "a.xhtml#c"),
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).toc.map { it.title to it.depth } shouldBe listOf("Part" to 0, "Chapter" to 1)
    }

    @Test
    fun `a book's subject headings become one tag per topic`() {
        val meta = book(
            "OEBPS/content.opf" to opf(
                listOf("a" to "a.xhtml"),
                metadata = """<dc:title>W</dc:title><dc:subject>Historical fiction</dc:subject>
                    <dc:subject>Napoleonic Wars, 1800-1815 -- Campaigns -- Russia -- Fiction</dc:subject>
                    <dc:subject>Russia -- History -- Alexander I, 1801-1825 -- Fiction</dc:subject>
                    <dc:subject>Russia -- Fiction</dc:subject>""",
            ),
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).metadata

        meta.genres shouldBe listOf("Historical fiction", "Napoleonic Wars", "Russia")
    }

    @Test
    fun `series and its position come from Calibre's metadata`() {
        val meta = book(
            "OEBPS/content.opf" to opf(
                listOf("a" to "a.xhtml"),
                metadata = """<dc:title>Vol 2</dc:title><dc:creator>A</dc:creator><dc:creator>B</dc:creator>
                    <meta name="calibre:series" content="Saga"/><meta name="calibre:series_index" content="2.0"/>""",
            ),
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).metadata

        listOf(meta.series, meta.seriesIndex, meta.author) shouldBe listOf("Saga", 2.0, "A, B")
    }

    @Test
    fun `an EPUB 3 collection's position reads as a decimal`() {
        val meta = book(
            "OEBPS/content.opf" to opf(
                listOf("a" to "a.xhtml"),
                metadata = """<meta property="belongs-to-collection" id="s">Saga</meta>
                    <meta refines="#s" property="group-position">1.5</meta>""",
            ),
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).metadata

        (meta.series to meta.seriesIndex) shouldBe ("Saga" to 1.5)
    }

    @Test
    fun `a description written as HTML reads as text`() {
        book(
            "OEBPS/content.opf" to
                opf(
                    listOf("a" to "a.xhtml"),
                    metadata = "<dc:description>&lt;p&gt;Hi &lt;b&gt;there&lt;/b&gt;&lt;/p&gt;</dc:description>",
                ),
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).metadata.description shouldBe "Hi there"
    }

    @Test
    fun `a book whose chapters are encrypted is reported as protected`() {
        val error = shouldThrow<EpubException> {
            book(
                "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml")),
                "OEBPS/a.xhtml" to "garbage",
                "META-INF/encryption.xml" to """<encryption><EncryptedData><CipherData>
                    <CipherReference URI="OEBPS/a.xhtml"/></CipherData></EncryptedData></encryption>""",
            )
        }

        error.kind shouldBe EpubException.Kind.Protected
    }

    @Test
    fun `a book that only obfuscates its fonts still opens`() {
        book(
            "OEBPS/content.opf" to opf(listOf("a" to "a.xhtml")),
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
            "META-INF/encryption.xml" to """<encryption><EncryptedData><CipherData>
                <CipherReference URI="OEBPS/fonts/f.otf"/></CipherData></EncryptedData></encryption>""",
        ).spine shouldBe listOf("OEBPS/a.xhtml")
    }

    @Test
    fun `a book laid out as fixed pages is told apart from one that reflows`() {
        book(
            "OEBPS/content.opf" to opf(
                listOf("a" to "a.xhtml"),
                metadata = """<dc:title>Comic</dc:title><meta property="rendition:layout">pre-paginated</meta>""",
            ),
            "OEBPS/a.xhtml" to xhtml("<p>x</p>"),
        ).isFixedLayout shouldBe true
    }

    @Test
    fun `a package that prefixes its tags is read the same`() {
        val opf = """<?xml version="1.0"?>
<opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">
  <opf:metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Prefixed</dc:title></opf:metadata>
  <opf:manifest><opf:item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></opf:manifest>
  <opf:spine><opf:itemref idref="a"/></opf:spine>
</opf:package>"""
        val parsed = book("OEBPS/content.opf" to opf, "OEBPS/a.xhtml" to xhtml("<p>x</p>"))

        (parsed.metadata.title to parsed.spine) shouldBe ("Prefixed" to listOf("OEBPS/a.xhtml"))
    }

    @Test
    fun `a relative, encoded path resolves to the zip entry`() {
        resolve("OEBPS/text", "../images/cover%20art.jpg") shouldBe "OEBPS/images/cover art.jpg"
    }

    @Test
    fun `a file that is not an EPUB says so`() {
        shouldThrow<EpubException> { EpubBook.parse { emptyMap() } }.kind shouldBe EpubException.Kind.NotAnEpub
    }
}
