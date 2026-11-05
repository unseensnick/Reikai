package reikai.novel.local.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLDecoder

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
    val isFixedLayout: Boolean = false,
) {
    companion object {
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
                metadata = readMetadata(opf),
                spine = spine,
                toc = toc.filter { it.path in spine },
                coverPath = coverOf(opf, items, manifest)?.let(::pathOf),
                mediaTypes = items.associate { pathOf(it) to it.attr("media-type") },
                isFixedLayout = opf.local("meta").any {
                    it.attr("property") == "rendition:layout" && it.text().trim() == "pre-paginated"
                },
            )
        }

        private fun readMetadata(opf: Document): EpubMetadata {
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
                description = dc("description").firstOrNull()?.let { Jsoup.parse(it).text() }?.ifBlank { null },
                genres = dc("subject"),
                language = dc("language").firstOrNull(),
                series = (collection?.text()?.trim()?.ifEmpty { null } ?: named("calibre:series"))?.ifEmpty { null },
                seriesIndex = (collectionIndex ?: named("calibre:series_index"))?.trim()?.toDoubleOrNull(),
            )
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
                    if (href != null && !title.isNullOrEmpty()) entries += entry(base, href, title, depth)
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
                    if (!title.isNullOrEmpty() && !src.isNullOrBlank()) entries += entry(base, src, title, depth)
                    walk(point, depth + 1)
                }
            }
            walk(map, 0)
            return entries
        }

        private fun entry(base: String, href: String, title: String, depth: Int): EpubTocEntry {
            val file = href.substringBefore('#')
            val fragment = href.substringAfter('#', "").ifEmpty { null }?.let(::decode)
            return EpubTocEntry(title.replace(WHITESPACE, " "), resolve(base, file), fragment, depth)
        }

        private const val CONTAINER = "META-INF/container.xml"
        private const val ENCRYPTION = "META-INF/encryption.xml"
        private const val DEFAULT_PACKAGE = "OEBPS/content.opf"
        private const val NCX_TYPE = "application/x-dtbncx+xml"
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

internal fun EpubArchive.readText(path: String): String? = read(setOf(path))[path]?.toString(Charsets.UTF_8)

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

private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(value)
