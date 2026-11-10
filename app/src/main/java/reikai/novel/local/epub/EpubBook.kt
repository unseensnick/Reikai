package reikai.novel.local.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import reikai.novel.content.decodePercentKeepingPlus
import java.nio.charset.Charset

/** The files inside one EPUB, by their path in the zip, read several at a time in one pass. */
fun interface EpubArchive {
    /** The bytes of each of [paths] the book has; a path it lacks is left out. */
    fun read(paths: Set<String>): Map<String, ByteArray>
}

/** One entry of a book's table of contents: [path] is the zip path of its file, [fragment] the anchor in it. */
data class EpubTocEntry(val title: String, val path: String, val fragment: String?, val depth: Int)

/** What a book says about itself, each field null when the package leaves it out. */
data class EpubMetadata(
    val title: String?,
    val author: String?,
    val description: String?,
    val genres: List<String>,
    val language: String?,
    val series: String?,
    val seriesIndex: Double?,
    val artist: String?,
    /** A status in a source's words, which Reikai's own exports record. */
    val status: String?,
    /** Written by Reikai's own export, whose contents list each chapter as it was stored. */
    val isReikaiExport: Boolean,
    /** The package's own identifier, which a newer download of the same book keeps. */
    val identifier: String? = null,
)

/**
 * One EPUB's structure: [spine] is the reading order as zip paths, [toc] the table of contents in its own
 * order (only entries whose file is in the spine), [mediaTypes] each manifest file's declared type.
 */
class EpubBook(
    val metadata: EpubMetadata,
    val spine: List<String>,
    val toc: List<EpubTocEntry>,
    val coverPath: String?,
    val mediaTypes: Map<String, String>,
    /** Laid out as fixed pages, as a comic is, rather than as text that reflows. */
    val isFixedLayout: Boolean,
) {
    /** The picture type the manifest declares for [path], or null when it declares no picture there. */
    fun imageType(path: String): String? = mediaTypes[path]?.takeIf { it.startsWith("image/") }

    companion object {
        /** Whether an archive holding [entryNames] is a book [parse] can open: a container, or its default package. */
        fun looksLikeEpub(entryNames: Collection<String>): Boolean = entryNames.map { it.replace('\\', '/') }
            .let { CONTAINER in it || DEFAULT_PACKAGE in it }

        fun parse(archive: EpubArchive): EpubBook {
            val packagePath = archive.readText(CONTAINER)?.let(::xml)?.firstLocal("rootfile")?.attr("full-path")
                ?.ifBlank { null } ?: DEFAULT_PACKAGE
            val opf = archive.readText(packagePath)?.let(::xml) ?: throw EpubException(EpubException.Kind.NotAnEpub)
            val base = parentOf(packagePath)
            val items = opf.local("item").filter { it.parent()?.localName() == "manifest" }
            val manifest = items.associateBy { it.attr("id") }
            fun pathOf(item: Element) = resolve(base, item.attr("href"))

            val spineElement = opf.firstLocal("spine")
            val spine = spineElement?.childrenLocal("itemref").orEmpty()
                .mapNotNull { manifest[it.attr("idref")] }
                .filter { it.attr("media-type").let { type -> type.isBlank() || "html" in type } }
                .map(::pathOf)
                .distinct()
            if (spine.isEmpty()) throw EpubException(EpubException.Kind.NoContent)
            if (encryptedPaths(archive).any { it in spine }) throw EpubException(EpubException.Kind.Protected)

            val navItem = items.firstOrNull { "nav" in it.attr("properties").split(' ') }
            val ncxItem = spineElement?.attr("toc")?.let(manifest::get)
                ?: items.firstOrNull { it.attr("media-type") == NCX_TYPE }
            val toc = navItem?.let { readNav(archive, pathOf(it)) }?.takeIf { it.isNotEmpty() }
                ?: ncxItem?.let { readNcx(archive, pathOf(it)) }.orEmpty()

            return EpubBook(
                metadata = readMetadata(opf, opf.firstLocal("package")?.attr("unique-identifier").orEmpty()),
                spine = spine,
                toc = toc.filter { it.path in spine },
                coverPath = coverOf(opf, items, manifest)?.let(::pathOf),
                mediaTypes = items.associate { pathOf(it) to it.attr("media-type") },
                isFixedLayout = opf.local("meta").any {
                    it.attr("property") == "rendition:layout" && it.text().trim() == "pre-paginated"
                },
            )
        }

        private fun readMetadata(opf: Document, uniqueId: String): EpubMetadata {
            val metadata = opf.firstLocal("metadata")
            fun dc(name: String) = metadata?.local(name).orEmpty().map { it.text().trim() }.filter { it.isNotEmpty() }
            val metas = metadata?.local("meta").orEmpty()
            fun named(name: String) = metas.firstOrNull { it.attr("name") == name }?.attr("content")?.trim()
            val collection = metas.firstOrNull { it.attr("property") == "belongs-to-collection" }
            val collectionIndex = collection?.attr("id")?.takeIf { it.isNotEmpty() }?.let { id ->
                metas.firstOrNull { it.attr("refines") == "#$id" && it.attr("property") == "group-position" }?.text()
            }
            return EpubMetadata(
                title = dc("title").firstOrNull(),
                author = dc("creator").takeIf { it.isNotEmpty() }?.joinToString(", "),
                description = dc("description").firstOrNull()?.let(::descriptionText)?.ifBlank { null },
                genres = dc("subject").map(::subjectTopic).filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
                language = dc("language").firstOrNull(),
                series = (collection?.text()?.trim()?.ifEmpty { null } ?: named("calibre:series"))?.ifEmpty { null },
                seriesIndex = (collectionIndex ?: named("calibre:series_index"))?.trim()?.toDoubleOrNull(),
                artist = illustrators(metadata, metas).takeIf { it.isNotEmpty() }?.joinToString(", "),
                status = metas.firstOrNull { it.attr("property") == "reikai:status" }?.text()?.trim()?.ifEmpty { null },
                isReikaiExport = dc("identifier").any { it.startsWith(REIKAI_IDENTIFIER_PREFIX) },
                identifier = metadata?.local("identifier")
                    ?.let { ids ->
                        ids.firstOrNull { uniqueId.isNotEmpty() && it.id() == uniqueId } ?: ids.firstOrNull()
                    }
                    ?.text()?.trim()?.ifEmpty { null },
            )
        }

        /**
         * A description as text with its paragraphs and line breaks kept: books write it as HTML (escaped in
         * the package), and flattening that to one line would run a source's paragraphs together.
         */
        private fun descriptionText(html: String): String {
            val body = Jsoup.parse(html).body()
            // A line break inside the HTML's own text is formatting, which a page shows as a space.
            body.getAllElements().flatMap { it.textNodes() }
                .forEach { it.text(it.wholeText.replace(SPACES_AND_BREAKS, " ")) }
            // wholeText already writes a line break for each <br>; a block ends its paragraph.
            body.select("p, div, li, h1, h2, h3, h4").forEach { it.after("\n\n") }
            return body.wholeText().lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
                .replace(BLANK_LINES, "\n\n").trim()
        }

        /** Contributors in an artist's role: EPUB 2's `opf:role` attribute, or EPUB 3's refining meta. */
        private fun illustrators(metadata: Element?, metas: List<Element>): List<String> {
            val roles = metas.filter { it.attr("property") == "role" }
                .associate { it.attr("refines").removePrefix("#") to it.text().trim() }
            return metadata?.local("contributor").orEmpty().filter { contributor ->
                val role = contributor.attr("opf:role").ifEmpty { roles[contributor.id()].orEmpty() }
                role in ARTIST_ROLES
            }.map { it.text().trim() }.filter { it.isNotEmpty() }
        }

        /** EPUB 3's cover-image property, else EPUB 2's cover meta, else an image named or id'd as a cover. */
        private fun coverOf(opf: Document, items: List<Element>, manifest: Map<String, Element>): Element? {
            val images = items.filter { it.attr("media-type").startsWith("image/") }
            val metaCover = opf.local("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
            return images.firstOrNull { "cover-image" in it.attr("properties").split(' ') }
                ?: metaCover?.let(manifest::get)?.takeIf { it.attr("media-type").startsWith("image/") }
                ?: images.firstOrNull { it.attr("id").contains("cover", ignoreCase = true) }
                ?: images.firstOrNull { it.attr("href").substringAfterLast('/').contains("cover", ignoreCase = true) }
        }

        /**
         * The files `encryption.xml` names. Most books carrying one only obfuscate embedded fonts, which is no
         * obstacle to reading, so only an encrypted chapter file makes a book unreadable.
         */
        private fun encryptedPaths(archive: EpubArchive): List<String> =
            archive.readText(ENCRYPTION)?.let(::xml)?.local("CipherReference")
                ?.map { resolve("", it.attr("URI")) }.orEmpty()

        private fun readNav(archive: EpubArchive, navPath: String): List<EpubTocEntry> {
            val doc = archive.readText(navPath)?.let(::xml) ?: return emptyList()
            val navs = doc.local("nav")
            val nav = navs.firstOrNull { it.attr("epub:type").split(' ').contains("toc") }
                ?: navs.firstOrNull { it.id() == "toc" } ?: navs.firstOrNull() ?: return emptyList()
            val base = parentOf(navPath)
            val entries = mutableListOf<EpubTocEntry>()
            fun walk(list: Element, depth: Int) {
                for (li in list.childrenLocal("li")) {
                    val link = li.childrenLocal("a").firstOrNull()
                        ?: li.childrenLocal("span").firstOrNull()?.firstLocal("a")
                    val title = (link ?: li.childrenLocal("span").firstOrNull())?.text()?.trim()
                    val href = link?.attr("href")?.takeIf { it.isNotBlank() }
                    if (href != null &&
                        !title.isNullOrEmpty()
                    ) {
                        entries += entry(base, href, title, depth, entries.lastOrNull())
                    }
                    li.children().firstOrNull { it.localName() == "ol" || it.localName() == "ul" }
                        ?.let { walk(it, depth + 1) }
                }
            }
            nav.children().firstOrNull { it.localName() == "ol" || it.localName() == "ul" }?.let { walk(it, 0) }
            return entries
        }

        // Only the navMap: an NCX's pageList points at every printed page number, which is not a chapter.
        private fun readNcx(archive: EpubArchive, ncxPath: String): List<EpubTocEntry> {
            val map = archive.readText(ncxPath)?.let(::xml)?.firstLocal("navMap") ?: return emptyList()
            val base = parentOf(ncxPath)
            val entries = mutableListOf<EpubTocEntry>()
            fun walk(parent: Element, depth: Int) {
                for (point in parent.childrenLocal("navPoint")) {
                    val title = point.childrenLocal("navLabel").firstOrNull()?.firstLocal("text")?.text()?.trim()
                    val src = point.childrenLocal("content").firstOrNull()?.attr("src")
                    if (!title.isNullOrEmpty() &&
                        !src.isNullOrBlank()
                    ) {
                        entries += entry(base, src, title, depth, entries.lastOrNull())
                    }
                    walk(point, depth + 1)
                }
            }
            walk(map, 0)
            return entries
        }

        /** A fragment-only [href] (`#ch2`) points into the [previous] entry's file, as other readers take it. */
        private fun entry(
            base: String,
            href: String,
            title: String,
            depth: Int,
            previous: EpubTocEntry?,
        ): EpubTocEntry {
            val file = href.substringBefore('#')
            val fragment = href.substringAfter('#', "").ifEmpty { null }?.let(::decode)
            val path = if (file.isEmpty() && previous != null) previous.path else resolve(base, file)
            return EpubTocEntry(title.replace(WHITESPACE, " "), path, fragment, depth)
        }

        /** What the identifier of a book Reikai exported starts with. */
        const val REIKAI_IDENTIFIER_PREFIX = "urn:reikai:"
        private const val CONTAINER = "META-INF/container.xml"
        private const val ENCRYPTION = "META-INF/encryption.xml"
        private const val DEFAULT_PACKAGE = "OEBPS/content.opf"
        private const val NCX_TYPE = "application/x-dtbncx+xml"
        private val ARTIST_ROLES = setOf("ill", "art")
        private val SPACES = Regex("""[ \t]+""")
        private val SPACES_AND_BREAKS = Regex("""\s+""")
        private val BLANK_LINES = Regex("""\n{3,}""")
        private val WHITESPACE = Regex("\\s+")
    }
}

/** Why a file could not be read as a book. */
class EpubException(val kind: Kind) : Exception(kind.message) {
    enum class Kind(val message: String) {
        NotAnEpub("Not an EPUB file"),
        NoContent("This EPUB has no readable content"),
        Protected("This EPUB is protected with DRM"),
    }
}

internal fun EpubArchive.readText(path: String): String? = read(setOf(path))[path]?.let(::epubText)

/** A book's XML or XHTML file as text: by its byte order mark, else its XML declaration, else as UTF-8. */
internal fun epubText(bytes: ByteArray): String {
    val bom = BYTE_ORDER_MARKS.firstOrNull { (mark) -> bytes.startsWith(mark) }
    if (bom != null) return String(bytes, bom.first.size, bytes.size - bom.first.size, bom.second)
    val head = String(bytes, 0, minOf(bytes.size, DECLARATION_SCAN), Charsets.ISO_8859_1)
    // A declaration readable as single bytes cannot be UTF-16 or UTF-32, whatever it claims.
    val declared = XML_ENCODING.find(head)?.groupValues?.get(1)
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?.takeUnless { it.name().startsWith("UTF-16") || it.name().startsWith("UTF-32") }
    return bytes.toString(declared ?: Charsets.UTF_8)
}

private fun ByteArray.startsWith(prefix: ByteArray) =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private val BYTE_ORDER_MARKS = listOf(
    byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to Charsets.UTF_8,
    byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE,
    byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE,
)
private const val DECLARATION_SCAN = 1024
private val XML_ENCODING = Regex("""^\s*<\?xml[^>]*?encoding\s*=\s*["']([\w.:-]+)["']""")

internal fun xml(text: String): Document = Jsoup.parse(text, "", Parser.xmlParser())

/** A tag's name without its namespace prefix, since books spell `opf:item`, `dc:title` and `item` alike. */
internal fun Element.localName(): String = tagName().substringAfter(':')

internal fun Element.local(name: String): List<Element> = getAllElements().filter { it.localName() == name }

internal fun Element.firstLocal(name: String): Element? = getAllElements().firstOrNull { it.localName() == name }

internal fun Element.childrenLocal(name: String): List<Element> = children().filter { it.localName() == name }

internal fun parentOf(path: String): String = path.substringBeforeLast('/', "")

/** [href] relative to the directory [base], as a zip path: decoded, with `.` and `..` resolved. */
internal fun resolve(base: String, href: String): String {
    if (href.isEmpty()) return href
    val decoded = decode(href.substringBefore('?'))
    val parts = ArrayDeque<String>()
    val start = if (decoded.startsWith('/')) emptyList() else base.split('/').filter { it.isNotEmpty() }
    for (part in start + decoded.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(part)
        }
    }
    return parts.joinToString("/")
}

private fun decode(value: String): String = decodePercentKeepingPlus(value) ?: value

/**
 * A `dc:subject` as one tag. Library-catalogued books (Project Gutenberg's among them) give subject headings
 * such as "Russia -- History -- Alexander I, 1801-1825 -- Fiction", whose commas the comma-joined genre list
 * would split into fragments, so a heading keeps only its topic: "Russia". Plain tags pass through.
 */
internal fun subjectTopic(subject: String): String =
    subject.substringBefore(" -- ").substringBefore(",").trim()
