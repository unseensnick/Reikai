package reikai.presentation.reader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter

/**
 * Unloads this manga chapter so the next load starts over. [fromSource] also drops the page list and
 * images [cache] holds of it, which the next page loader would otherwise serve again.
 */
fun ReaderChapter.unloadForReload(fromSource: Boolean, cache: ChapterCache) {
    val oldPages = pages
    // Emptied before the loader is recycled: an online loader caches whatever page list its chapter
    // still holds on recycle, off-thread, and would put back the list dropped below.
    state = ReaderChapter.State.Wait
    pageLoader?.recycle()
    pageLoader = null
    if (fromSource) {
        oldPages?.forEach { page -> page.imageUrl?.let(cache::removeImage) }
        chapter.toDomainChapter()?.let(cache::removePageList)
    }
}
