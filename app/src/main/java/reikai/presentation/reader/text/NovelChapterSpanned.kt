package reikai.presentation.reader.text

import android.text.Html
import android.text.SpannableStringBuilder
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import reikai.novel.content.NovelChapterAddress
import reikai.novel.content.NovelImageSources

/**
 * A processed chapter as the native renderer's text, before any of its styling: the markup reshaped for
 * `Html.fromHtml`, parsed, and its block separators collapsed. The renderer styles this for the screen,
 * and read aloud with no reader open takes its paragraphs from it, so both count the same lines.
 * [displayWidth] picks a `srcset` candidate; [imageGetter] is null where no picture is drawn.
 */
internal fun chapterSpanned(
    html: String,
    baseUrl: String?,
    displayWidth: Int,
    imageGetter: Html.ImageGetter?,
    checkpoint: () -> Unit = {},
): SpannableStringBuilder {
    val normalized = normalizeHtmlForRendering(html, baseUrl, displayWidth)
    checkpoint()
    val spanned = Html.fromHtml(normalized, Html.FROM_HTML_MODE_LEGACY, imageGetter, NovelChapterTags)
    checkpoint()
    return SpannableStringBuilder(spanned).also { NovelTextRenderer.collapseBlankLines(it) }
}

/** The chapter's paragraphs as read aloud counts them in the native renderer, with no renderer open. */
internal fun chapterParagraphs(html: String, baseUrl: String?): List<String> =
    readAloudParagraphs(listOf(chapterSpanned(html, baseUrl, displayWidth = 0, imageGetter = null))).map { it.text }

/**
 * `Html.fromHtml` has no CSS and no `picture` support, so the markup is reshaped into what it can
 * read. Failing open leaves the chapter as it was rather than blanking it. It also ignores table,
 * definition-list and `pre` layout, which are rebuilt here into the lines a WebView shows for them.
 */
private fun normalizeHtmlForRendering(html: String, baseUrl: String?, displayWidth: Int): String = try {
    val doc = Jsoup.parse(html)
    doc.select("style, script").remove()
    NovelChapterTags.prepare(doc, baseUrl)
    doc.select("video source, audio source").remove()
    // `source` is a void element and Html.fromHtml corrupts the rest of the document without one.
    NovelImageSources.unwrapPictures(doc)
    rebuildBlockLayout(doc)
    doc.select("img").forEach { img ->
        NovelImageSources.srcsetCandidate(img, displayWidth)?.let { img.attr("src", it) }
        if (img.hasAttr("src")) img.attr("src", NovelChapterAddress.absolute(baseUrl, img.attr("src")))
        val parent = img.parent()
        if (parent?.tagName() == "p" || parent?.tagName() == "div") {
            liftOutOfText(img, parent)
        } else {
            img.wrap("<p style=\"text-align:center;\"></p>")
        }
    }
    // The WebView mode loads the chapter with this base, so its relative links arrive absolute and
    // open in the browser. Left relative here they reach the link policy as a non-http URL, which
    // it blocks, and the tap did nothing with nothing said.
    doc.select("a[href]").forEach { it.attr("href", NovelChapterAddress.absolute(baseUrl, it.attr("href"))) }
    doc.body().html()
} catch (e: Exception) {
    logcat("NovelTextRenderer", LogPriority.WARN) { "Chapter markup left unprepared\n" + e.asLog() }
    html
}

/** A table row, a term and its definition each take a line, cells a space apart, and a `pre` keeps
 *  its line breaks, as they lay out in a WebView. */
private fun rebuildBlockLayout(doc: Document) {
    doc.select("td, th").forEach { cell ->
        if (cell.previousElementSibling() != null) cell.before(" ")
        cell.tagName("span")
    }
    doc.select("tr, dt, dd").tagName("div")
    doc.select("pre").forEach { it.html(it.html().replace("\n", "<br>")) }
}

/**
 * The WebView's stylesheet lays every image out as a block, so text either side of one reads as its
 * own line there. The paragraph is split around it to match, the image taking the centred block an
 * image outside a paragraph gets; a side left with nothing to show is dropped. A paragraph showing
 * only the image is centred where it stands.
 */
private fun liftOutOfText(img: Element, parent: Element) {
    val siblings = parent.childNodes()
    // Another picture counts: two in one paragraph each take their own block, as the page lays them out.
    val showsSomethingElse = siblings.any {
        it !== img &&
            ((it is TextNode && !it.isBlank) || (it is Element && (it.hasText() || it.isOrHoldsImage())))
    }
    if (!showsSomethingElse) {
        parent.attr("style", "text-align:center;")
        return
    }
    val after = parent.shallowClone().appendChildren(siblings.drop(siblings.indexOf(img) + 1))
    parent.after(after)
    parent.after(Element("p").attr("style", "text-align:center;").appendChild(img))
    listOf(parent, after)
        .filter { !it.hasText() && !it.isOrHoldsImage() }
        .forEach(Element::remove)
}

private fun Element.isOrHoldsImage() = tagName() == "img" || selectFirst("img") != null
