package reikai.novel.export

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.novel.toNovel
import reikai.novel.host.SourceNovel
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubArchive
import reikai.novel.local.epub.EpubBook
import reikai.novel.local.epub.EpubChapters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubWriterTest {

    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
    private val dataPng = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

    private val book = EpubExportBook(
        title = "A & B",
        author = "Someone",
        description = "About <it>",
        language = "en",
        genres = listOf("Fantasy"),
        identifier = "urn:reikai:novel:src:/test",
        cover = ExportPicture(png, "image/png"),
        chapters = listOf(
            ExportChapter("Chapter 1") { "<p>One&nbsp;line<br>next</p><img src=\"$dataPng\" alt=\"pic\">" },
            ExportChapter("Chapter 2") { "<p onclick=\"x()\">Two</p><script>bad()</script><img src=\"$dataPng\">" },
            ExportChapter("Chapter 3") { "<p>Three</p><img src=\"https://example.com/x.png\">" },
        ),
    )

    private suspend fun written(writer: EpubWriter = EpubWriter()): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        writer.write(book, out)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return entries
    }

    @Test
    fun `the mimetype comes first and is stored uncompressed`() = runTest {
        val out = ByteArrayOutputStream()
        EpubWriter().write(book, out)
        val first = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { it.nextEntry }

        (first.name to first.method) shouldBe ("mimetype" to ZipEntry.STORED)
    }

    @Test
    fun `every chapter and the contents are well-formed XML`() = runTest {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val malformed = written()
            .filterKeys { it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") }
            .filterValues { runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(it)) }.isFailure }
            .keys

        malformed shouldBe emptySet()
    }

    @Test
    fun `a picture used twice is written once`() = runTest {
        written().keys.count { it.startsWith("OEBPS/images/image-") } shouldBe 1
    }

    @Test
    fun `nothing that runs survives`() = runTest {
        val chapter = written().getValue("OEBPS/text/chapter-0002.xhtml").toString(Charsets.UTF_8)

        listOf("script", "onclick").filter { it in chapter } shouldBe emptyList()
    }

    @Test
    fun `a web picture is dropped, since the book cannot fetch it`() = runTest {
        written().getValue("OEBPS/text/chapter-0003.xhtml").toString(Charsets.UTF_8) shouldNotContain_ "example.com"
    }

    @Test
    fun `without pictures no picture file is written`() = runTest {
        written(EpubWriter(includePictures = false)).keys.filter { "images/" in it } shouldBe emptyList()
    }

    @Test
    fun `the book reads back through the local source's reader with its chapters`() = runTest {
        val files = written()
        val archive = EpubArchive { paths -> paths.mapNotNull { p -> files[p]?.let { p to it } }.toMap() }
        val parsed = EpubBook.parse(archive)

        EpubChapters.list(parsed, archive).map { it.title } shouldBe listOf("Chapter 1", "Chapter 2", "Chapter 3")
    }

    @Test
    fun `the read back book keeps its title, author and cover`() = runTest {
        val files = written()
        val parsed = EpubBook.parse { paths -> paths.mapNotNull { p -> files[p]?.let { p to it } }.toMap() }

        Triple(parsed.metadata.title, parsed.metadata.author, parsed.coverPath) shouldBe
            Triple("A & B", "Someone", "OEBPS/images/cover.png")
    }

    @Test
    fun `a source's details survive an export and an import`() = runTest {
        val out = ByteArrayOutputStream()
        EpubWriter().write(
            book.copy(artist = "Drawer", status = "Ongoing", description = "First <it>.\n\nSecond\nline."),
            out,
        )
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        val meta = EpubBook.parse { paths -> paths.mapNotNull { p -> files[p]?.let { p to it } }.toMap() }.metadata

        listOf(meta.artist, meta.status, meta.description, meta.genres) shouldBe
            listOf("Drawer", "Ongoing", "First <it>.\n\nSecond\nline.", listOf("Fantasy"))
    }

    // The local source hands over a book's text decoded, so storing it must not decode it again.
    @Test
    fun `a description that spells an entity keeps it through an export and an import`() = runTest {
        val out = ByteArrayOutputStream()
        EpubWriter().write(book.copy(description = "A &amp; B <tag>"), out)
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        val meta = EpubBook.parse { paths -> paths.mapNotNull { p -> files[p]?.let { p to it } }.toMap() }.metadata

        SourceNovel(path = "p", name = meta.title, summary = meta.description).toNovel(LocalNovelSource.ID)
            .description shouldBe "A &amp; B <tag>"
    }

    @Test
    fun `a chapter keeps its text`() = runTest {
        written().getValue("OEBPS/text/chapter-0001.xhtml").toString(Charsets.UTF_8) shouldContain "One"
    }

    @Test
    fun `a chapter whose own heading names it gets no second heading`() = runTest {
        val nested = book.copy(
            chapters = listOf(
                ExportChapter("I") {
                    "<div><header><h2>I</h2></header><p>Text</p></div>"
                },
            ),
        )
        val out = ByteArrayOutputStream()
        EpubWriter().write(nested, out)
        val chapter = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name.endsWith("chapter-0001.xhtml") }
            zip.readBytes().toString(Charsets.UTF_8)
        }

        Regex("<h2[^>]*>I</h2>").findAll(chapter).count() shouldBe 1
    }

    @Test
    fun `a script address and foreign markup do not survive`() = runTest {
        val scripted = book.copy(
            chapters = listOf(
                ExportChapter("I") {
                    "<p>Text</p><map><area href=\"javascript:alert(1)\"></map>" +
                        "<svg><set attributeName=\"href\" to=\"javascript:x\"/></svg>"
                },
            ),
        )
        val out = ByteArrayOutputStream()
        EpubWriter().write(scripted, out)
        val chapter = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name.endsWith("chapter-0001.xhtml") }
            zip.readBytes().toString(Charsets.UTF_8)
        }

        listOf("javascript:", "<svg").filter { it in chapter } shouldBe emptyList()
    }

    private val linked =
        "<p><a href=\"#n\">to n</a></p><p><a href=\"#x\">to x</a></p><p><a href=\"#gone\">gone</a></p>" +
            "<p><a href=\"next.html\">next</a></p><p id=\"n\">N</p><p xml:id=\"x\">X</p>"

    private suspend fun firstChapter(html: String): String {
        val out = ByteArrayOutputStream()
        EpubWriter().write(book.copy(chapters = listOf(ExportChapter("I") { html })), out)
        return ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name.endsWith("chapter-0001.xhtml") }
            zip.readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `a jump to a place in the chapter stays a link and any other relative link keeps its text`() = runTest {
        Regex("href=\"(#[^\"]*|next[^\"]*)\"").findAll(firstChapter(linked)).map { it.groupValues[1] }.toList() shouldBe
            listOf("#n", "#x")
    }

    @Test
    fun `a target named by xml id gets the id its link names`() = runTest {
        firstChapter(linked) shouldContain "<p id=\"x\">X</p>"
    }

    /** Both readers follow a link to an old-style named anchor, so the book must too. */
    @Test
    fun `a target named by name gets the id its link names`() = runTest {
        val chapter = firstChapter("<p><a href=\"#fn1\">1</a></p><p><a name=\"fn1\">note</a></p>")

        listOf("href=\"#fn1\"", "id=\"fn1\"").filter { it !in chapter } shouldBe emptyList()
    }

    @Test
    fun `a percent-encoded jump to a place in the chapter stays a link`() = runTest {
        firstChapter("<p><a href=\"#note%20one\">1</a></p><p id=\"note one\">note</p>") shouldContain
            "href=\"#note%20one\""
    }

    /** An import joins a short chapter of any other book into the next, taking it for a part's title page. */
    @Test
    fun `a short chapter stays its own chapter through an export and an import`() = runTest {
        val out = ByteArrayOutputStream()
        val chapters = listOf(ExportChapter("Note") { "<p>Thanks.</p>" }, ExportChapter("Chapter 1") { "<p>Text</p>" })
        EpubWriter().write(book.copy(chapters = chapters), out)
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        val archive = EpubArchive { paths -> paths.mapNotNull { p -> files[p]?.let { p to it } }.toMap() }

        EpubChapters.list(EpubBook.parse(archive), archive).map { it.title } shouldBe listOf("Note", "Chapter 1")
    }

    @Test
    fun `a cancelled export reads no chapter after the cancel`() = runTest {
        var read = 0
        val chapters = List(3) {
            ExportChapter("C$it") {
                read++
                "<p>x</p>"
            }
        }
        val job = Job()
        launch(job) { EpubWriter().write(book.copy(chapters = chapters), ByteArrayOutputStream()) { job.cancel() } }
        job.join()

        read shouldBe 1
    }

    @Test
    fun `a picture typed image jpg is declared by its registered type`() {
        EpubWriter.decodeDataAddress("data:image/jpg;base64," + Base64.getEncoder().encodeToString(png))!!.mime shouldBe
            "image/jpeg"
    }

    private infix fun String.shouldNotContain_(text: String) = (text in this) shouldBe false
}
