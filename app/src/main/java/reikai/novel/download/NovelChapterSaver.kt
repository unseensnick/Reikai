package reikai.novel.download

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.network.NovelImageRequests
import reikai.novel.source.NovelSource

/**
 * Saves a chapter's HTML as its downloaded copy, which the reader prefers to asking the source. Apart
 * from the download manager because a page fetch saves one too, and building the manager restores and
 * may start the whole queue.
 */
@Inject
@SingleIn(AppScope::class)
class NovelChapterSaver(
    private val provider: NovelDownloadProvider,
    private val cache: NovelDownloadCache,
    private val imageRequests: NovelImageRequests,
) {

    /** False when there is nothing to save or no storage to save it in. */
    suspend fun save(novel: Novel, chapter: NovelChapter, source: NovelSource, html: String): Boolean {
        if (html.isBlank()) return false
        // Embed inline images so the saved file reads offline (see inlineChapterImages).
        val selfContained = inlineChapterImages(html, source.site, imageRequests.forSource(source.id))
        if (!provider.writeChapter(novel, chapter, selfContained)) return false
        cache.addChapter(novel, chapter)
        return true
    }
}
