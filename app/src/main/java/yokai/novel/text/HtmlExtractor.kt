package yokai.novel.text

import org.jsoup.Jsoup

/**
 * Extract paragraphs of plain text from a chapter's HTML payload (what an lnreader plugin's
 * `parseChapter` returns). Drops markup, collapses whitespace within each paragraph, preserves
 * paragraph boundaries.
 *
 * Most plugins emit one `<p>` per paragraph; that's the primary signal. For sources that return
 * a flat text blob without `<p>` tags, we fall back to splitting on blank lines, then on single
 * line breaks. Result is an empty list if no readable text was found.
 */
fun htmlToParagraphs(html: String): List<String> {
    if (html.isBlank()) return emptyList()
    val doc = Jsoup.parseBodyFragment(html)
    val pTagged = doc.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
    if (pTagged.isNotEmpty()) return pTagged

    val whole = doc.body().wholeText()
    return whole.split(Regex("\\n{2,}"))
        .flatMap { it.split('\n') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
