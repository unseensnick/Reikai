package reikai.novel.source.ireader

import ireader.core.source.model.ImageBase64
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.Page
import ireader.core.source.model.PageComplete
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Text
import org.jsoup.nodes.Entities
import reikai.novel.content.NovelChapterAddress
import reikai.novel.content.NovelHtmlUtils

/**
 * A chapter's IReader pages as the HTML the novel reader renders: a text page's paragraphs by the plain-text rule,
 * and a picture per image, whether linked or embedded. A page that is only a link to its content is fetched
 * through [resolve], and one the source cannot resolve is left out; video and subtitles are left out,
 * since no novel reader plays them.
 */
internal suspend fun List<Page>.toChapterHtml(resolve: suspend (PageUrl) -> PageComplete?): String =
    mapNotNull { it.toHtml(resolve) }.joinToString("\n")

private suspend fun Page.toHtml(resolve: suspend (PageUrl) -> PageComplete?): String? = when (this) {
    is Text -> NovelHtmlUtils.plainTextParagraphs(text).ifEmpty { null }
    is ImageUrl -> "<img src=\"${Entities.escape(url)}\">"
    is ImageBase64 -> "<img src=\"${Entities.escape(dataUri(data))}\">"
    is PageUrl -> resolve(this)?.toHtml(resolve)
    else -> null
}

// A source may hand over a whole data URI or the bare base64 of the picture.
private fun dataUri(
    data: String,
): String = if (NovelChapterAddress.isDataAddress(data)) data else "data:image/png;base64,$data"
