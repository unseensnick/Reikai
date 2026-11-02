package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.data.cache.ChapterCache

/**
 * Unloads this manga chapter so the next load starts over. [fromSource] also drops the page list and
 * images [cache] holds of it, which the next page loader would otherwise serve again.
 */
suspend fun ReaderChapter.unloadForReload(fromSource: Boolean, cache: ChapterCache) {
    val oldPages = pages
    // From the source the state is emptied before the loader is recycled: an online loader caches whatever page
    // list its chapter still holds on recycle, off-thread, and would put back the list dropped below.
    if (fromSource) state = ReaderChapter.State.Wait
    pageLoader?.recycle()
    pageLoader = null
    state = ReaderChapter.State.Wait
    if (fromSource) {
        oldPages?.forEach { page -> page.imageUrl?.let { cache.removeImage(it) } }
        cache.removePageList(chapter)
    }
}
