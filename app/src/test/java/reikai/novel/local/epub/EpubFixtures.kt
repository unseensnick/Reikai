package reikai.novel.local.epub

/** A book built in memory: [files] by zip path, the package at `OEBPS/content.opf` unless given. */
internal fun epub(files: Map<String, String>, binary: Map<String, ByteArray> = emptyMap()): EpubArchive {
    val container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
    val all =
        mapOf("META-INF/container.xml" to container.toByteArray()) + files.mapValues { it.value.toByteArray() } + binary
    return EpubArchive { paths -> paths.mapNotNull { path -> all[path]?.let { path to it } }.toMap() }
}

/** A package listing [chapters] (manifest id to href) in spine order, with an optional nav or NCX. */
internal fun opf(
    chapters: List<Pair<String, String>>,
    nav: String? = null,
    ncx: String? = null,
    metadata: String = "<dc:title>Book</dc:title>",
    extraManifest: String = "",
) = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata>
  <manifest>
    ${chapters.joinToString("\n") { (id, href) ->
    """<item id="$id" href="$href" media-type="application/xhtml+xml"/>"""
}}
    ${nav?.let { """<item id="nav" href="$it" media-type="application/xhtml+xml" properties="nav"/>""" }.orEmpty()}
    ${ncx?.let { """<item id="ncx" href="$it" media-type="application/x-dtbncx+xml"/>""" }.orEmpty()}
    $extraManifest
  </manifest>
  <spine${if (ncx != null) " toc=\"ncx\"" else ""}>${chapters.joinToString("") {
    """<itemref idref="${it.first}"/>"""
}}</spine>
</package>"""

internal fun xhtml(body: String, head: String = "") = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title>$head</head><body>$body</body></html>"""

/** An EPUB 3 nav listing [entries] (label to href), nested one level for each label starting with `>`. */
internal fun nav(vararg entries: Pair<String, String>): String {
    val items = StringBuilder()
    var open = false
    for ((label, href) in entries) {
        if (label.startsWith(">")) {
            if (!open) {
                items.setLength(items.length - "</li>".length)
                items.append("<ol>")
                open = true
            }
            items.append("""<li><a href="$href">${label.removePrefix(">")}</a></li>""")
        } else {
            if (open) {
                items.append("</ol></li>")
                open = false
            }
            items.append("""<li><a href="$href">$label</a></li>""")
        }
    }
    if (open) items.append("</ol></li>")
    return xhtml("""<nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc"><ol>$items</ol></nav>""")
}

/** Renders every chapter of [archive] as plain text, pictures named by path. */
internal fun chapterTexts(archive: EpubArchive): List<Pair<String, String>> {
    val book = EpubBook.parse(archive)
    val chapters = EpubChapters.list(book, archive)
    return chapters.indices.map { index ->
        val html = EpubChapters.render(book, archive, chapters, index) { path, _ -> "data:$path" }
        chapters[index].title to org.jsoup.Jsoup.parse(html).text()
    }
}
