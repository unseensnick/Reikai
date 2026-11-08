package reikai.presentation.reader.text

import reikai.data.coil.NovelImage
import reikai.novel.content.NovelChapterAddress

/**
 * How a chapter's picture opens full screen, one rule both renderers follow (the page takes [opensOnTap]'s
 * answer as `PICTURE_OPENS_ON_TAP`). A long press on a loaded block picture always opens it and never starts
 * a text selection, since a picture has no text to select. A tap opens it only while scrolling: paged, a tap
 * turns the page, wherever it lands.
 */
object NovelPictureGesture {

    fun opensOnTap(paged: Boolean): Boolean = !paged

    /** What coil loads for a picture at [source] in a chapter of [sourceId]: inline data as it is, a web address
     *  with its source's client and headers. Null for anything else. */
    fun modelOf(source: String?, sourceId: String?): Any? = when {
        source.isNullOrBlank() -> null
        source.startsWith("data:") -> source
        NovelChapterAddress.isWebAddress(source) -> NovelImage(source, sourceId)
        else -> null
    }
}
