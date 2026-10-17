package reikai.presentation.reader.text

import android.content.Context
import android.text.Spannable
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.core.net.toUri
import eu.kanade.tachiyomi.util.system.openInBrowser
import reikai.novel.content.NovelChapterAddress
import reikai.presentation.reader.NovelChapterNavigationClient

/**
 * Replaces the `URLSpan`s `Html.fromHtml` produces with spans that route a tap through the reader's
 * own navigation policy.
 *
 * A stock `URLSpan` fires an `ACTION_VIEW` for whatever the href says, including `intent://`, so the
 * native renderer would trust chapter markup that the WebView path refuses. Both renderers ask the
 * same question, which is the point of it being a pure function.
 */
object NovelChapterLinks {

    /** [baseUrl] is the chapter's address, against which the policy blocks a link to the chapter itself, as
     *  the WebView's document base does. */
    fun apply(spannable: Spannable, context: Context, baseUrl: String?, onAnchor: (TextView, Int) -> Unit) {
        val base = NovelChapterAddress.trustedBase(baseUrl)
        val urls = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        urls.forEach { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            spannable.removeSpan(span)
            spannable.setSpan(PolicyLinkSpan(span.url, context, base, onAnchor), start, end, flags)
        }
    }

    private class PolicyLinkSpan(
        private val url: String,
        private val context: Context,
        private val baseUrl: String?,
        private val onAnchor: (TextView, Int) -> Unit,
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            // A jump within the chapter, by fragment or by the chapter's own address, was rewritten to an
            // anchor the renderer placed, so only a fragment naming nothing reaches the policy.
            if (url.startsWith(NovelChapterTags.ANCHOR_HREF)) {
                url.removePrefix(NovelChapterTags.ANCHOR_HREF).toIntOrNull()?.let { onAnchor(widget as TextView, it) }
                return
            }
            val decision = NovelChapterNavigationClient.decide(url, baseUrl, hasGesture = true)
            if (decision == NovelChapterNavigationClient.Decision.OPEN_EXTERNALLY) {
                context.openInBrowser(url.toUri())
            }
        }
    }
}
