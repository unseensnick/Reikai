package reikai.novel.export

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A book to write: [chapters] in reading order, each body HTML as the reader stores it. */
data class EpubExportBook(
    val title: String,
    val author: String?,
    val description: String?,
    val language: String?,
    val genres: List<String>,
    val identifier: String,
    val cover: ExportPicture?,
    val chapters: List<ExportChapter>,
)

/** One chapter, its HTML read only when the writer reaches it, so a long novel is never held whole. */
class ExportChapter(val title: String, val html: suspend () -> String)

data class ExportPicture(val bytes: ByteArray, val mime: String)

/**
 * Writes an EPUB 3 with an EPUB 2 table of contents beside it, so older readers find the chapters too.
 * Each chapter becomes well-formed XHTML: pictures carried as `data:` addresses become files of their own
 * (one per distinct picture), web pictures are dropped since a book on disk cannot fetch them, and nothing
 * that runs is kept. [includePictures] false drops every picture. Design: docs/dev/subsystems/local-novels.md.
 */
class EpubWriter(private val includePictures: Boolean = true) {

    suspend fun write(book: EpubExportBook, out: OutputStream, onChapter: (done: Int) -> Unit = {}) {
        ZipOutputStream(out).use { zip ->
            zip.stored("mimetype", "application/epub+zip".toByteArray())
            zip.text("META-INF/container.xml", CONTAINER)
            zip.text("OEBPS/style.css", STYLE)

            // Each picture is written when first met, so only its name and type are kept for the manifest.
            val pictures = LinkedHashMap<String, Pair<String, String>>()
            val files = book.chapters.mapIndexed { index, chapter ->
                val name = "text/chapter-%04d.xhtml".format(index + 1)
                val xhtml = chapterXhtml(chapter, book.language, pictures) { path, picture ->
                    zip.bytes("OEBPS/$path", picture.bytes)
                }
                zip.text("OEBPS/$name", xhtml)
                onChapter(index + 1)
                name
            }
            val cover = book.cover?.takeIf { includePictures }?.let { "images/cover.${extensionOf(it.mime)}" to it }
            cover?.let { (path, picture) -> zip.bytes("OEBPS/$path", picture.bytes) }

            zip.text("OEBPS/nav.xhtml", nav(book, files))
            zip.text("OEBPS/toc.ncx", ncx(book, files))
            zip.text("OEBPS/content.opf", opf(book, files, pictures.values.toList(), cover))
        }
    }

    private suspend fun chapterXhtml(
        chapter: ExportChapter,
        language: String?,
        pictures: MutableMap<String, Pair<String, String>>,
        writePicture: (path: String, picture: ExportPicture) -> Unit,
    ): String {
        val body = Jsoup.parseBodyFragment(chapter.html()).body()
        body.select(
            "script, noscript, iframe, frame, frameset, object, embed, applet, form, input, button, style, link, meta, base",
        )
            .remove()
        for (element in body.getAllElements()) {
            element.attributes().asList()
                .filter { it.key.startsWith("on") || it.key == "contenteditable" || ':' in it.key }
                .forEach { element.removeAttr(it.key) }
        }
        for (img in body.select("img")) {
            val src = img.attr("src")
            val picture = if (includePictures) decodeDataAddress(src) else null
            if (picture == null) {
                img.remove()
                continue
            }
            val hash = sha1(picture.bytes)
            val path = pictures.getOrPut(hash) {
                val path = "images/image-${pictures.size + 1}.${extensionOf(picture.mime)}"
                writePicture(path, picture)
                path to picture.mime
            }.first
            img.attributes().asList().map { it.key }.filter { it != "alt" }.forEach(img::removeAttr)
            img.attr("src", "../$path").attr("alt", img.attr("alt"))
        }
        // Links inside a chapter that pointed at the source's site lead nowhere in a book; keep their text.
        body.select("a[href]").filterNot { it.attr("href").startsWith("http") }.forEach { it.unwrap() }

        val document = Document.createShell("")
        document.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        val html = document.child(0)
        html.attr("xmlns", XHTML_NS).attr("xmlns:epub", EPUB_NS).attr("xml:lang", language ?: UNDETERMINED)
        document.head().appendElement("meta").attr("charset", "utf-8")
        document.head().appendElement("title").text(chapter.title)
        document.head().appendElement(
            "link",
        ).attr("rel", "stylesheet").attr("type", "text/css").attr("href", "../style.css")
        val section = document.body().appendElement("section").attr("epub:type", "chapter")
        if (!startsWithHeading(body, chapter.title)) section.appendElement("h2").text(chapter.title)
        section.insertChildren(section.childNodeSize(), body.childNodes().toList())
        return XML_HEADER + "<!DOCTYPE html>\n" + html.outerHtml()
    }

    private fun nav(book: EpubExportBook, files: List<String>): String {
        val items = book.chapters.zip(files).joinToString("\n") { (chapter, file) ->
            "      <li><a href=\"$file\">${escape(chapter.title)}</a></li>"
        }
        return """$XML_HEADER<!DOCTYPE html>
<html xmlns="$XHTML_NS" xmlns:epub="$EPUB_NS" xml:lang="${escape(book.language ?: UNDETERMINED)}">
<head><meta charset="utf-8"/><title>${escape(book.title)}</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>${escape(book.title)}</h1>
    <ol>
$items
    </ol>
  </nav>
</body>
</html>
"""
    }

    private fun ncx(book: EpubExportBook, files: List<String>): String {
        val points = book.chapters.zip(files).mapIndexed { index, (chapter, file) ->
            """    <navPoint id="nav-${index + 1}" playOrder="${index + 1}">
      <navLabel><text>${escape(chapter.title)}</text></navLabel>
      <content src="$file"/>
    </navPoint>"""
        }.joinToString("\n")
        return """$XML_HEADER<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="${escape(book.identifier)}"/></head>
  <docTitle><text>${escape(book.title)}</text></docTitle>
  <navMap>
$points
  </navMap>
</ncx>
"""
    }

    private fun opf(
        book: EpubExportBook,
        files: List<String>,
        pictures: List<Pair<String, String>>,
        cover: Pair<String, ExportPicture>?,
    ): String {
        val manifest = buildList {
            add("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            add("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            add("""<item id="css" href="style.css" media-type="text/css"/>""")
            cover?.let { (path, picture) ->
                add("""<item id="cover" href="$path" media-type="${picture.mime}" properties="cover-image"/>""")
            }
            files.forEachIndexed { index, file ->
                add("""<item id="c${index + 1}" href="$file" media-type="application/xhtml+xml"/>""")
            }
            pictures.forEachIndexed { index, (path, mime) ->
                add("""<item id="img${index + 1}" href="$path" media-type="$mime"/>""")
            }
        }.joinToString("\n    ")
        val spine = files.indices.joinToString("\n    ") { """<itemref idref="c${it + 1}"/>""" }
        val metadata = buildList {
            add("""<dc:identifier id="book-id">${escape(book.identifier)}</dc:identifier>""")
            add("<dc:title>${escape(book.title)}</dc:title>")
            add("<dc:language>${escape(book.language ?: UNDETERMINED)}</dc:language>")
            book.author?.let { add("<dc:creator>${escape(it)}</dc:creator>") }
            book.description?.let { add("<dc:description>${escape(it)}</dc:description>") }
            book.genres.forEach { add("<dc:subject>${escape(it)}</dc:subject>") }
            add("""<meta property="dcterms:modified">${modifiedNow()}</meta>""")
            if (cover != null) add("""<meta name="cover" content="cover"/>""")
        }.joinToString("\n    ")
        return """$XML_HEADER<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    $metadata
  </metadata>
  <manifest>
    $manifest
  </manifest>
  <spine toc="ncx">
    $spine
  </spine>
</package>
"""
    }

    /** The chapter opens with a heading naming it, however deep the book's markup puts the heading. */
    private fun startsWithHeading(body: Element, title: String): Boolean {
        val heading = body.selectFirst("h1, h2, h3, h4")?.text()?.trim()?.ifEmpty { null } ?: return false
        val opensWithIt = body.text().trim().startsWith(heading)
        return opensWithIt &&
            (title.contains(heading, ignoreCase = true) || heading.contains(title.trim(), ignoreCase = true))
    }

    private fun ZipOutputStream.stored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.text(name: String, text: String) = bytes(name, text.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.bytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    companion object {
        /** A fresh identifier for a book that has no stable one of its own. */
        fun newIdentifier(): String = "urn:uuid:${UUID.randomUUID()}"

        /** A `data:` picture's bytes and type, or null for any other address or type. Parsed by hand, not by a
         *  regex, since a picture's base64 runs to megabytes. */
        internal fun decodeDataAddress(src: String): ExportPicture? {
            if (!src.startsWith("data:")) return null
            val marker = src.indexOf(";base64,")
            if (marker < 0) return null
            val mime = src.substring(5, marker).substringBefore(';').ifEmpty { "image/jpeg" }.lowercase()
            if (mime !in PICTURE_TYPES) return null
            val data = src.substring(marker + BASE64_MARKER.length)
            return runCatching { ExportPicture(Base64.getMimeDecoder().decode(data), mime) }.getOrNull()
        }

        private fun extensionOf(mime: String): String = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            else -> "jpg"
        }

        private fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun escape(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private const val BASE64_MARKER = ";base64,"
        private val PICTURE_TYPES =
            setOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/svg+xml")

        /** BCP 47's tag for a language nobody stated, rather than guessing English. */
        private const val UNDETERMINED = "und"
        private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        private const val XHTML_NS = "http://www.w3.org/1999/xhtml"
        private const val EPUB_NS = "http://www.idpf.org/2007/ops"
        private fun modifiedNow(): String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        private val CONTAINER = """$XML_HEADER<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
        private const val STYLE = "body { margin: 0 5%; line-height: 1.5; }\n" +
            "p { margin: 0 0 0.8em; }\n" +
            "img { max-width: 100%; height: auto; }\n" +
            "h1, h2, h3 { text-align: center; }\n"
    }
}
