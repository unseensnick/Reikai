package reikai.novel.local.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import reikai.novel.content.elementNamed

/**
 * Where a chapter starts: the top of spine file [spineIndex], or inside it at the element [fragment] names
 * (an id), or at its [heading]th heading for a file the table of contents does not divide.
 */
data class EpubCut(val spineIndex: Int, val fragment: String? = null, val heading: Int? = null) {
    val isTop: Boolean get() = fragment == null && heading == null
}

/**
 * What a chapter is known by from one version of its book to the next: the file and anchor its contents
 * entry points at, or the [heading] it is cut at by that heading's text and how many cut headings before it
 * share the text, never a place in the spine or among the headings, so a chapter added to the book moves no
 * other chapter. [heading] is opaque: [EpubCut.heading] is what slices the file.
 */
data class EpubChapterKey(val path: String, val fragment: String? = null, val heading: String? = null)

data class EpubChapterRef(val title: String, val start: EpubCut, val key: EpubChapterKey)

/**
 * The book read as one stream in spine order, cut where the table of contents points: a chapter runs from
 * its cut to the next one, across files, so a file the contents skip continues the chapter before it and a
 * file holding several chapters is divided at their anchors. A book whose contents point at nothing gets a
 * chapter per file, and a file with several top-level headings and no other division is cut at them.
 * Design and the cases each rule answers: docs/dev/subsystems/local-novels.md.
 */
object EpubChapters {

    fun list(book: EpubBook, archive: EpubArchive): List<EpubChapterRef> {
        val parsed = archive.read(book.spine.toSet()).mapValues { (_, bytes) -> parseContent(bytes) }
        val refs = fromToc(book).ifEmpty { fromFiles(book, parsed) }
        // Every entry of a book Reikai wrote is a real chapter, however short, so none is taken for a part title.
        if (book.metadata.isReikaiExport) return refs
        return joinNearlyEmpty(book, refs) { path -> parsed[path]?.clone() }
    }

    private fun fromToc(book: EpubBook): List<EpubChapterRef> {
        val cuts = book.toc
            .mapNotNull { entry -> book.spine.indexOf(entry.path).takeIf { it >= 0 }?.let { entry to it } }
            .sortedBy { it.second }
        val merged = mutableListOf<Pair<EpubChapterRef, Int>>()
        for ((entry, index) in cuts) {
            val cut = EpubCut(index, entry.fragment)
            val title = cleanTitle(entry.title)
            val last = merged.lastOrNull()
            if (last != null && last.first.start == cut) {
                // A part and its first chapter at one place: one chapter, named for both.
                if (entry.depth > last.second) {
                    merged[merged.lastIndex] = last.first.copy(title = "${last.first.title} - $title") to entry.depth
                }
            } else {
                merged += EpubChapterRef(title, cut, EpubChapterKey(entry.path, entry.fragment)) to entry.depth
            }
        }
        // Whatever comes before the first entry (a cover, a title page) opens the first chapter.
        return merged.mapIndexed { n, (ref, _) -> if (n == 0) ref.copy(start = EpubCut(0)) else ref }
    }

    private fun fromFiles(book: EpubBook, parsed: Map<String, Document?>): List<EpubChapterRef> =
        book.spine.flatMapIndexed { index, path ->
            val document = parsed[path] ?: return@flatMapIndexed emptyList()
            if (!document.hasText()) return@flatMapIndexed emptyList()
            val headings = topHeadings(document.body())
            if (book.spine.size == 1 && headings.size > 1) {
                val seen = mutableMapOf<String, Int>()
                headings.mapIndexed { n, heading ->
                    val text = heading.text().replace(WHITESPACE, " ").trim()
                    val occurrence = seen.getOrDefault(text, 0).also { seen[text] = it + 1 }
                    EpubChapterRef(
                        heading.text().trim(),
                        EpubCut(index, heading = n),
                        EpubChapterKey(path, heading = "$occurrence:$text"),
                    )
                }
            } else {
                listOf(EpubChapterRef(titleOf(document) ?: "${index + 1}", EpubCut(index), EpubChapterKey(path)))
            }
        }.mapIndexed { n, ref -> if (n == 0) ref.copy(start = EpubCut(0)) else ref }

    /**
     * A chapter with almost no text and no picture joins the one after it, named for both: a part's title
     * page ("BOOK ONE"), or a heading the contents list apart from the text under its subtitle.
     */
    private fun joinNearlyEmpty(
        book: EpubBook,
        refs: List<EpubChapterRef>,
        document: (String) -> Document?,
    ): List<EpubChapterRef> {
        val joined = mutableListOf<EpubChapterRef>()
        var pending: EpubChapterRef? = null
        for ((index, ref) in refs.withIndex()) {
            val current = pending?.let { it.copy(title = "${it.title} - ${ref.title}") } ?: ref
            pending = null
            val bodies = if (index <
                refs.lastIndex
            ) {
                slice(book, refs, index, document).map { it.second.body() }
            } else {
                null
            }
            val nearlyEmpty = bodies != null &&
                bodies.sumOf { it.text().length } < NEARLY_EMPTY_CHARS &&
                bodies.none { body -> listOf("img", "image", "svg").any { body.local(it).isNotEmpty() } }
            if (nearlyEmpty) pending = current else joined += current
        }
        return joined
    }

    /**
     * The HTML of [chapters]'s [index]th chapter: its body content with the book's stylesheets. Each `<img>`
     * points at [pictureAddress] of its zip path, read only when the reader shows it, so no size is too big;
     * a stylesheet picture is inlined through [inline] (handed a zip path, answering a `data:` address or null
     * to drop it), since neither renderer fetches what a stylesheet names.
     */
    fun render(
        book: EpubBook,
        archive: EpubArchive,
        chapters: List<EpubChapterRef>,
        index: Int,
        pictureAddress: (path: String) -> String,
        inline: (path: String, bytes: ByteArray) -> String?,
    ): String {
        val files = filesOf(book, chapters, index)
        val bytesByPath = archive.read(files.toSet())
        val slices = slice(book, chapters, index) { path -> bytesByPath[path]?.let(::parseContent) }

        val out = Document.createShell("")
        // The output follows its own document's settings, not the parsed files', and pretty-printing folds the
        // line breaks of a paragraph the book styles as preformatted.
        out.outputSettings().prettyPrint(false)
        val styles = mutableListOf<StyleSource>()
        for ((path, document) in slices) {
            document.head().local("link").filter { "stylesheet" in it.attr("rel") }.forEach {
                val cssPath = resolve(parentOf(path), it.attr("href"))
                styles += StyleSource(base = parentOf(cssPath), path = cssPath)
            }
            // An XML parse holds a style block as text, an HTML one as data.
            document.head().local("style").forEach {
                styles += StyleSource(base = parentOf(path), text = it.data().ifEmpty { it.wholeText() })
            }
            val body = document.body()
            prepare(body, path, files)
            out.body().appendElement("div").insertChildren(0, body.childNodes().toList())
        }

        val cssFiles = archive.read(styles.mapNotNull { it.path }.toSet())
        val css = styles.distinct().map {
            it.base to (it.text ?: cssFiles[it.path]?.toString(Charsets.UTF_8).orEmpty())
        }

        val cssUrls = css.flatMap { (base, text) ->
            CSS_URL.findAll(text).map { resolve(base, it.groupValues[2]) }.filter { book.imageType(it) != null }
        }
        val bytes = archive.read(cssUrls.toSet())

        for (img in out.body().local("img")) {
            val path = img.attr(PATH_ATTR)
            img.removeAttr(PATH_ATTR)
            if (path.isEmpty()) img.remove() else img.attr("src", pictureAddress(path))
        }
        val styleText = css.joinToString("\n") { (base, text) -> cleanCss(text, base, bytes, inline) }
        if (styleText.isNotBlank()) out.head().appendElement("style").appendChild(DataNode(styleText))
        return out.head().html() + out.body().html()
    }

    /** The spine files [chapters]'s [index]th chapter covers, in reading order. */
    private fun filesOf(book: EpubBook, chapters: List<EpubChapterRef>, index: Int): List<String> {
        val start = chapters[index].start
        val end = chapters.getOrNull(index + 1)?.start
        val lastFile = when {
            end == null -> book.spine.lastIndex
            end.isTop -> end.spineIndex - 1
            else -> end.spineIndex
        }.coerceAtLeast(start.spineIndex)
        return (start.spineIndex..lastFile).map { book.spine[it] }
    }

    /** Each file the chapter covers, as its own document cut to the chapter's start and end. */
    private fun slice(
        book: EpubBook,
        chapters: List<EpubChapterRef>,
        index: Int,
        document: (String) -> Document?,
    ): List<Pair<String, Document>> {
        val start = chapters[index].start
        val end = chapters.getOrNull(index + 1)?.start
        return filesOf(book, chapters, index).mapIndexedNotNull { offset, path ->
            val doc = document(path) ?: return@mapIndexedNotNull null
            val body = doc.body()
            val spineIndex = start.spineIndex + offset
            // Both ends are found before either cut: a heading cut counts headings in the whole file.
            val from = if (offset == 0 && !start.isTop) target(body, start)?.let { lift(it, body) } else null
            val until = if (end != null && spineIndex == end.spineIndex && !end.isTop) {
                target(body, end)?.let { lift(it, body) }
            } else {
                null
            }
            from?.let { keepFrom(body, it) }
            until?.let { keepUntil(body, it) }
            path to doc
        }
    }

    /**
     * A contents label with a caption in front of its chapter name, as Project Gutenberg builds them from the
     * illustration that opens a chapter ("He rode a black horse. CHAPTER III."), reads as the chapter name.
     */
    internal fun cleanTitle(title: String): String {
        val match = CAPTIONED_TITLE.matchEntire(title) ?: return title
        // A web novel's own name can end the same way ("Chapter 45 - Showdown! Part 2"), but it numbers or names
        // the chapter before that end, where a caption is prose.
        val caption = match.groupValues[1]
        if (caption.any(Char::isDigit) || NUMBERED_START.containsMatchIn(caption)) return title
        return match.groupValues[2].trim()
    }

    /** The element a cut points at, or null when the book names an anchor its file does not have. */
    private fun target(body: Element, cut: EpubCut): Element? = when {
        cut.fragment != null -> elementNamed(body, cut.fragment)
        cut.heading != null -> topHeadings(body).getOrNull(cut.heading)
        else -> null
    }

    /**
     * The anchor as the element it opens: an empty `<a id>` at the start of a heading stands for the heading,
     * so a cut neither leaves the heading's first words with the chapter before nor drops them.
     */
    private fun lift(element: Element, body: Element): Element {
        var current = element
        while (true) {
            val parent = current.parent() ?: return current
            if (parent == body) return current
            if (parent.childNodes().takeWhile { it !== current }.any { it.hasContent() }) return current
            current = parent
        }
    }

    private fun keepFrom(body: Element, target: Element) {
        var node: Node = target
        while (node !== body) {
            val parent = node.parentNode() ?: return
            parent.childNodes().takeWhile { it !== node }.toList().forEach(Node::remove)
            node = parent
        }
    }

    private fun keepUntil(body: Element, target: Element) {
        var node: Node = target
        while (node !== body) {
            val parent = node.parentNode() ?: return
            parent.childNodes().dropWhile { it !== node }.drop(1).toList().forEach(Node::remove)
            node = parent
        }
        target.remove()
    }

    /**
     * Readies one file's body for the reader: an SVG that only frames a picture becomes that picture (the
     * renderers drop SVG), each picture records its zip path, and links stay only where they still lead.
     * Scripts are left to the reader's sanitizer, which honours keep-embedded-JS as for any source.
     */
    private fun prepare(body: Element, path: String, files: List<String>) {
        val base = parentOf(path)
        for (svg in body.local("svg")) {
            val image = svg.firstLocal("image") ?: continue
            val href = image.attr("xlink:href").ifEmpty { image.attr("href") }
            if (href.isEmpty() || svg.local("text").isNotEmpty()) continue
            svg.replaceWith(Element("img").attr("src", href).attr("alt", svg.firstLocal("title")?.text().orEmpty()))
        }
        for (img in body.local("img")) {
            val src = img.attr("src").ifEmpty { img.attr("xlink:href") }
            if (src.isEmpty() || src.startsWith("data:") || "://" in src) continue
            img.attr(PATH_ATTR, resolve(base, src))
            img.removeAttr("srcset")
        }
        for (link in body.local("a")) {
            val href = link.attr("href")
            if (href.isEmpty() || "://" in href || href.startsWith("mailto:")) continue
            val file = href.substringBefore('#')
            val fragment = href.substringAfter('#', "")
            val target = if (file.isEmpty()) path else resolve(base, file)
            if (target in files && fragment.isNotEmpty()) link.attr("href", "#$fragment") else link.removeAttr("href")
        }
        for (element in body.getAllElements()) {
            if (element.hasAttr("style")) element.attr("style", stripThemeDeclarations(element.attr("style")))
        }
    }

    /**
     * A stylesheet the reader can use: no fonts or imports, and no colours or backgrounds, which would fight
     * the reader's theme. Pictures it points at are inlined, since the chapter has no file to resolve them.
     */
    private fun cleanCss(
        text: String,
        base: String,
        bytes: Map<String, ByteArray>,
        inline: (path: String, bytes: ByteArray) -> String?,
    ): String {
        return stripThemeDeclarations(text.replace(FONT_FACE, "").replace(IMPORT, ""))
            .replace(CSS_URL) { match ->
                val path = resolve(base, match.groupValues[2])
                bytes[path]?.let { inline(path, it) }?.let { "url(\"$it\")" } ?: "none"
            }
    }

    private fun stripThemeDeclarations(css: String): String = css.replace(THEME_DECLARATION, "")

    private fun topHeadings(root: Element): List<Element> {
        val levels = listOf("h1", "h2", "h3")
        val level = levels.firstOrNull { tag -> root.local(tag).size > 1 } ?: return emptyList()
        return root.local(level)
    }

    private fun titleOf(document: Document): String? =
        listOf("h1", "h2", "h3").firstNotNullOfOrNull {
            document.body().firstLocal(it)?.text()?.trim()?.ifEmpty { null }
        }
            ?: document.head().firstLocal("title")?.text()?.trim()?.ifEmpty { null }

    private fun Document.hasText(): Boolean = body().text().isNotBlank() || body().local("img").isNotEmpty()

    private fun Node.hasContent(): Boolean = when (this) {
        is TextNode -> !isBlank
        is Element -> true
        else -> false
    }

    /** One stylesheet a chapter's files use: a linked file at [path], or a `<style>` block's [text]. */
    private data class StyleSource(val base: String, val path: String? = null, val text: String? = null)

    private const val NEARLY_EMPTY_CHARS = 100
    private val CAPTIONED_TITLE = Regex(
        """(.+?[.!?”"’)\]]\s+)((?:chapter|book|part|volume|canto|section)\s*[ivxlcdm\d]+\.?)""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBERED_START =
        Regex("""^\s*(?:chapter|book|part|volume|vol|canto|section|arc|episode|ch)\b""", RegexOption.IGNORE_CASE)
    private const val PATH_ATTR = "data-epub-path"
    private val WHITESPACE = Regex("""\s+""")
    private val CSS_URL = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")

    // Every brace escaped: Android's ICU engine rejects a bare `}` that Java's own accepts.
    private val FONT_FACE = Regex("""@font-face\s*\{[^\}]*\}""", RegexOption.IGNORE_CASE)
    private val IMPORT = Regex("""@import[^;]*;""", RegexOption.IGNORE_CASE)
    private val THEME_DECLARATION = Regex(
        """(?<![-\w])(color|background|background-color|background-image)\s*:[^;\}]*;?""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * A content file as a document: XML first, since XHTML writes `<a id="x"/>`, which an HTML parser reads as an
 * open tag swallowing the rest of the page, then HTML for the EPUB 2 files that are not well-formed XML.
 */
internal fun parseContent(bytes: ByteArray): Document? {
    val text = epubText(bytes)
    val asXml = runCatching { Jsoup.parse(text, "", Parser.xmlParser()) }.getOrNull()
    val xmlBody = asXml?.firstLocal("body")
    val document = if (xmlBody != null) {
        Document.createShell("").also { shell ->
            asXml.firstLocal("head")?.childNodes()?.toList()?.forEach { shell.head().appendChild(it) }
            shell.body().insertChildren(0, xmlBody.childNodes().toList())
            xmlBody.attributes().forEach { shell.body().attr(it.key, it.value) }
        }
    } else {
        Jsoup.parse(text)
    }
    document.outputSettings().prettyPrint(false)
    return document
}
