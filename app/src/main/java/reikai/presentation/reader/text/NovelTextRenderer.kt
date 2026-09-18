package reikai.presentation.reader.text

import android.content.Context
import android.graphics.Color
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import reikai.novel.content.NovelImageSources
import reikai.presentation.reader.NovelTextScale
import tachiyomi.core.common.util.system.logcat

/**
 * Turns a processed chapter into styled text across [ChapterTextBlock]'s chunk views.
 *
 * Ported from tsundoku (`textview/NovelTextRenderer.kt`), re-plumbed off their host Activity and
 * preference class. It is handed pipeline output, which a renderer must not process again, and it
 * takes the HTML built for a WebView sink, so plain text arrives already wrapped in paragraphs.
 * Details in docs/dev/plans/content-layer-reader-surface.md.
 */
class NovelTextRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    /** A link to a place in its own chapter was tapped: [AnchorSpan] [Int] of the chunk view's text. */
    private val onAnchor: (widget: TextView, index: Int) -> Unit,
) {

    /**
     * [paragraphSpacing] and [paragraphIndent] are multiples of the font size, matching how tsundoku
     * stores them. [onTextSet] fires once the views hold the finished text, and the returned job ends
     * after that, or without it when the render is superseded or the block dropped.
     */
    fun render(
        block: ChapterTextBlock,
        html: String,
        fontSize: Int,
        paragraphSpacing: Float,
        paragraphIndent: Float,
        selectable: Boolean,
        bionic: Boolean,
        /**
         * The width an image is bounded to, in pixels: the column the text is drawn in, not the
         * display. The caller supplies it because no chunk view exists yet when a render starts, so
         * measuring one here silently fell back to the display width and clipped every image by the
         * side margins.
         */
        contentWidth: Int,
        /**
         * The chapter's own URL. Relative image sources and links are resolved against it, since
         * `Html.fromHtml` has no base of its own and the link policy blocks anything not http(s), and
         * it is sent as the Referer for an image some hosts would otherwise refuse.
         */
        baseUrl: String?,
        /** Runs a change that moves text, holding the reader's line across it. */
        holdAcross: (change: () -> Unit) -> Unit,
        onTextSet: () -> Unit,
    ): Job {
        // In sp, as the text is, so spacing keeps its proportion to the text at any system font size.
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSize.toFloat(),
            context.resources.displayMetrics,
        )
        val token = ++block.renderToken
        val spacingPx = (paragraphSpacing * textSizePx).toInt()
        val indentPx = (paragraphIndent * textSizePx).toInt()

        return scope.launch {
            val imageGetter = NovelImageGetter(
                context = context,
                scope = scope,
                contentWidthPx = contentWidth,
                refererUrl = baseUrl?.let { it.trimEnd('/') + "/" },
                textSizePx = textSizePx,
                textColor = { block.chunkViews.firstOrNull()?.currentTextColor ?: Color.GRAY },
                resolveView = block::chunkViewFor,
                onImagesReady = { views ->
                    scope.launch {
                        remeasureForImages(views, selectable, block, holdAcross)
                        // Only now, since a saved position is landed as soon as this clears, and the chapter
                        // is still growing while any picture's chunk waits on its re-measure. A superseded
                        // render's images finishing says nothing about this render's.
                        if (token == block.renderToken) block.imagesLoading = false
                        // A layout is what re-checks the chapter, and none may follow: nothing arrived, or a
                        // re-set text kept its height, which a TextView only redraws.
                        block.container.requestLayout()
                    }
                },
            )

            val spannable = withContext(Dispatchers.Default) {
                // The normalising walks and copies the whole chapter, which for a downloaded one with its
                // images inlined is megabytes, so it belongs here rather than on the caller's thread.
                val spanned = Html.fromHtml(
                    normalizeHtmlForRendering(html, baseUrl),
                    Html.FROM_HTML_MODE_LEGACY,
                    imageGetter,
                    NovelChapterTags,
                )
                SpannableStringBuilder(spanned)
                    .also { collapseBlankLines(it) }
                    .also { resizeSizedText(it) }
                    .also { placeImages(it, textSizePx.toInt(), spacingPx) }
                    .also { NovelChapterTags.placeRules(it) }
                    .also { shrinkScripts(it) }
                    .also { NovelChapterLinks.apply(it, context, onAnchor) }
                    .also { if (bionic) NovelBionicSpans.apply(it) }
            }

            // A later render or the block's removal has replaced this one: the rest is work nobody reads.
            if (token != block.renderToken || block.discarded) return@launch
            val chunks = withContext(Dispatchers.Default) {
                chunkRanges(spannable).map { (start, end) ->
                    SpannableStringBuilder(spannable.subSequence(start, end)).also {
                        if (spacingPx > 0 || indentPx > 0) applyParagraphSpans(it, spacingPx, indentPx)
                    }
                }
            }

            if (token != block.renderToken || block.discarded) return@launch
            block.ensureChunkCount(chunks.size)
            if (chunks.isEmpty()) {
                onTextSet()
                return@launch
            }

            // Precomputing the layout off the main thread is what keeps a long chapter from stalling
            // on first draw, and it is incompatible with a selectable view, so only one is possible.
            val params = if (selectable) null else TextViewCompat.getTextMetricsParams(block.chunkViews.first())
            val precomputed = withContext(Dispatchers.Default) {
                params?.let { p -> chunks.map { PrecomputedTextCompat.create(it, p) } }
            }
            if (token != block.renderToken || block.discarded) return@launch

            block.clearSelections()
            // The views are styled by now, and a picture's span reads their spacing when it is laid out.
            chunks.forEachIndexed { i, chunk ->
                NovelTextStyle.holdImagesOffLineSpacing(chunk, block.chunkViews[i].lineSpacingExtra)
            }
            if (precomputed == null) {
                chunks.forEachIndexed { i, chunk -> block.chunkViews[i].text = chunk }
            } else {
                precomputed.forEachIndexed { i, text ->
                    // Throws when the view's metrics moved since the params were taken, which a
                    // settings change between the two dispatches can do. Plain text is the fallback.
                    try {
                        TextViewCompat.setPrecomputedText(block.chunkViews[i], text)
                    } catch (_: IllegalArgumentException) {
                        block.chunkViews[i].text = chunks[i]
                    }
                }
            }
            // After the text is set, so every image span has a view to find and re-measure.
            block.imagesLoading = imageGetter.startLoading()
            onTextSet()
        }
    }

    /**
     * An image changes its span's height after layout, and neither layout re-reads a drawable's bounds
     * (`PrecomputedText` caches its measure, a selectable `DynamicLayout` reflows only on an edit), so
     * the text is set again. Every view is measured first and all set in one [holdAcross]: the pictures
     * grow the chapter above the line, and a second hold is refused while the first's correction waits.
     * Gated on the block, not the view being attached, as the render is. Details in the reader record.
     */
    private suspend fun remeasureForImages(
        views: List<TextView>,
        selectable: Boolean,
        block: ChapterTextBlock,
        holdAcross: (() -> Unit) -> Unit,
    ) {
        if (block.discarded) return
        val snapshots = views.mapNotNull { view -> view.text?.let { view to it } }
        views.filter { it.text == null }.forEach(View::requestLayout)
        // Copied rather than re-set as itself, so the framework treats it as new text; the copy keeps the
        // chapter's emphasis, links, images and paragraph spans, as restyling does. Copied here on the main
        // thread, since read aloud edits the spans of the text on screen from it.
        val copies = snapshots.map { (view, text) -> view to SpannableStringBuilder(text) }
        val remeasured: List<Pair<TextView, CharSequence>> = if (selectable) {
            copies
        } else {
            val params = copies.map { (view, _) -> TextViewCompat.getTextMetricsParams(view) }
            withContext(Dispatchers.Default) {
                copies.mapIndexed { i, (view, text) -> view to PrecomputedTextCompat.create(text, params[i]) }
            }
        }
        if (block.discarded) return
        holdAcross {
            remeasured.forEach { (view, text) ->
                if (text is PrecomputedTextCompat) {
                    try {
                        TextViewCompat.setPrecomputedText(view, text)
                    } catch (_: IllegalArgumentException) {
                        view.requestLayout()
                    }
                } else {
                    view.text = text
                }
            }
        }
    }

    /**
     * `Html.fromHtml` has no CSS and no `picture` support, so the markup is reshaped into what it can
     * read. Failing open leaves the chapter as it was rather than blanking it. It also ignores table,
     * definition-list and `pre` layout, which are rebuilt here into the lines a WebView shows for them.
     */
    private fun normalizeHtmlForRendering(html: String, baseUrl: String?): String = try {
        val doc = Jsoup.parse(html, baseUrl.orEmpty())
        doc.select("style, script").remove()
        NovelChapterTags.prepare(doc)
        doc.select("video source, audio source").remove()
        // `source` is a void element and Html.fromHtml corrupts the rest of the document without one.
        NovelImageSources.unwrapPictures(doc)
        rebuildBlockLayout(doc)
        val targetWidth = context.resources.displayMetrics.widthPixels
        doc.select("img").forEach { img ->
            NovelImageSources.srcsetCandidate(img, targetWidth)?.let { img.attr("src", it) }
            resolveAgainstBase(img, "src")
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
        // A fragment or empty href names nothing to open, as reader.js leaves it, so the policy blocks it.
        doc.select("a[href]")
            .filterNot { link ->
                val href = link.attr("href").trim()
                href.isEmpty() || href.startsWith("#") || href.startsWith(NovelChapterTags.ANCHOR_HREF)
            }
            .forEach { resolveAgainstBase(it, "href") }
        doc.body().html()
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Chapter markup left unprepared" }
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
     * image outside a paragraph gets; a side left with nothing to show is dropped.
     */
    private fun liftOutOfText(img: Element, parent: Element) {
        val siblings = parent.childNodes()
        val showsSomethingElse = siblings.any {
            it !== img && ((it is TextNode && !it.isBlank) || (it is Element && it.hasText()))
        }
        if (!showsSomethingElse) return
        val after = parent.shallowClone().appendChildren(siblings.drop(siblings.indexOf(img) + 1))
        parent.after(after)
        parent.after(Element("p").attr("style", "text-align:center;").appendChild(img))
        listOf(parent, after)
            .filter { !it.hasText() && it.selectFirst("img") == null }
            .forEach(Element::remove)
    }

    /** Jsoup answers with the empty string when a value needs a base and there is none, so the
     *  original is kept there. A `data:` source needs no base and is skipped rather than re-parsed. */
    private fun resolveAgainstBase(element: Element, attribute: String) {
        if (element.attr(attribute).startsWith("data:")) return
        val resolved = element.absUrl(attribute)
        if (resolved.isNotBlank()) element.attr(attribute, resolved)
    }

    private fun applyParagraphSpans(spannable: SpannableStringBuilder, spacingPx: Int, indentPx: Int) {
        var i = 0
        var paragraphStart = 0
        while (i < spannable.length) {
            if (spannable[i] == '\n' || i == spannable.length - 1) {
                val paragraphEnd = i + 1
                // A blank line the source asked for costs one line height, as the break it came from
                // does in a WebView. Spacing it like a paragraph would multiply every stacked break.
                val isBlankLine = paragraphEnd - paragraphStart == 1 && spannable[paragraphStart] == '\n'
                // A picture's line keeps its own margins (ChapterImageSpan), as an img takes no p styling.
                val isPicture = spannable.getSpans(
                    paragraphStart,
                    paragraphEnd,
                    ChapterImageSpan::class.java,
                ).isNotEmpty()
                if (isPicture) {
                    paragraphStart = paragraphEnd
                    i++
                    continue
                }
                if (spacingPx > 0 && !isBlankLine) {
                    spannable.setSpan(
                        ParagraphSpacingSpan(spacingPx),
                        paragraphStart,
                        paragraphEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (indentPx > 0 && !isBlankLine) {
                    spannable.setSpan(
                        ParagraphIndentSpan(indentPx),
                        paragraphStart,
                        paragraphEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                paragraphStart = paragraphEnd
            }
            i++
        }
    }

    companion object {
        private const val CHUNK_TARGET_CHARS = 6_000

        /**
         * Splits at the first paragraph boundary past every [CHUNK_TARGET_CHARS], so each chunk's layout
         * stays small and no chunk ends mid-paragraph. The newline a chunk ends on is left out: a layout
         * ending in one draws an empty line after it, which widened every chunk seam by a line.
         * [ParagraphSpacingSpan] and [NovelTextStyle] give the chunk's last line what it stood for.
         */
        internal fun chunkRanges(text: CharSequence): List<Pair<Int, Int>> {
            val length = text.length
            if (length == 0) return emptyList()
            val ranges = ArrayList<Pair<Int, Int>>(length / CHUNK_TARGET_CHARS + 1)
            var start = 0
            while (start < length) {
                var end = (start + CHUNK_TARGET_CHARS).coerceAtMost(length)
                if (end < length) {
                    while (end < length && text[end] != '\n') end++
                } else if (text[length - 1] == '\n') {
                    end = length - 1
                }
                ranges.add(start to end)
                start = end + 1
            }
            return ranges
        }

        /**
         * `Html.fromHtml` separates blocks with a blank line, which would sit under the paragraph
         * spacing and draw a wider gap than the same settings do in a WebView. Exactly one break per
         * run is taken, which is that separator: a longer run is line breaks the source asked for,
         * and flattening those too made "Remove extra spacing" invisible in this renderer.
         */
        internal fun collapseBlankLines(text: SpannableStringBuilder) {
            var i = text.length - 1
            while (i > 0) {
                if (text[i] == '\n' && text[i - 1] == '\n') {
                    text.delete(i, i + 1)
                    while (i > 0 && text[i - 1] == '\n') i--
                }
                i--
            }
        }

        /**
         * A picture alone on its line is laid out as the page lays out an `img`, 1em clear above and below
         * (ChapterImageSpan). Above, the margin collapses with what the paragraph before already leaves, as
         * CSS margins do: its spacing, or a picture's own 1em. One sharing a line with text keeps its span.
         */
        internal fun placeImages(text: SpannableStringBuilder, emPx: Int, spacingPx: Int) {
            text.getSpans(0, text.length, ImageSpan::class.java).sortedBy(text::getSpanStart).forEach { image ->
                val start = text.getSpanStart(image)
                val end = text.getSpanEnd(image)
                val alone = (start == 0 || text[start - 1] == '\n') && (end == text.length || text[end] == '\n')
                if (!alone) return@forEach
                val above = if (start == 0) {
                    0
                } else {
                    val previousStart = text.lastIndexOf('\n', start - 2) + 1
                    val previousIsPicture = text.getSpans(
                        previousStart,
                        start - 1,
                        ChapterImageSpan::class.java,
                    ).isNotEmpty()
                    if (previousIsPicture) emPx else spacingPx
                }
                val flags = text.getSpanFlags(image)
                text.removeSpan(image)
                val top = (emPx - above).coerceAtLeast(0)
                text.setSpan(ChapterImageSpan(image.drawable, image.source, top, emPx), start, end, flags)
            }
        }

        /** `Html.fromHtml` sizes headings, `big` and `small` its own way; the page's table replaces them. */
        internal fun resizeSizedText(text: SpannableStringBuilder) {
            text.getSpans(0, text.length, RelativeSizeSpan::class.java).forEach { span ->
                val size = NovelTextScale.forFromHtmlSize(span.sizeChange) ?: return@forEach
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                val flags = text.getSpanFlags(span)
                text.removeSpan(span)
                if (size != 1f) text.setSpan(RelativeSizeSpan(size), start, end, flags)
            }
        }

        /** `Html.fromHtml` raises `sup` and lowers `sub` at full size; the WebView page sets both smaller. */
        internal fun shrinkScripts(text: SpannableStringBuilder) {
            val scripts: List<Any> = text.getSpans(0, text.length, SuperscriptSpan::class.java).toList() +
                text.getSpans(0, text.length, SubscriptSpan::class.java)
            scripts.forEach { script ->
                val start = text.getSpanStart(script)
                val end = text.getSpanEnd(script)
                text.setSpan(RelativeSizeSpan(NovelTextScale.SCRIPT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
