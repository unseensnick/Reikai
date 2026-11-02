package reikai.novel.download

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.network.NovelImageRequests
import reikai.novel.source.NovelSource

/**
 * Saves a chapter's HTML as its downloaded copy, which the reader prefers to asking the source. Apart
 * from the download manager because a page fetch saves one too, and building the manager reads the
 * persisted queue from the database.
 */
@Inject
@SingleIn(AppScope::class)
class NovelChapterSaver(
    private val provider: NovelDownloadProvider,
    private val cache: NovelDownloadCache,
    private val imageRequests: NovelImageRequests,
    private val chapterRepo: NovelChapterRepository,
) {

    suspend fun save(novel: Novel, chapter: NovelChapter, source: NovelSource, html: String): SaveResult =
        write(novel, chapter, prepare(source, html))

    /**
     * [html] with its images embedded so the saved file reads offline (see [inlineChapterImages]). Apart from
     * [write] because it fetches every image, and the download lanes write one at a time.
     */
    suspend fun prepare(source: NovelSource, html: String): String =
        if (html.isBlank()) html else inlineChapterImages(html, source.site, imageRequests.forSource(source.id))

    /** Writes [selfContained], from [prepare], as [chapter]'s downloaded copy. */
    suspend fun write(novel: Novel, chapter: NovelChapter, selfContained: String): SaveResult {
        if (selfContained.isBlank()) return SaveResult.FAILED
        val fileName = provider.chapterFileName(chapter)
        // Without the url hash, chapters of one name share a file. The first copy saved keeps it, as manga's
        // downloader keeps the first folder: replacing it would show this text under the other chapter.
        if (provider.findChapterFile(novel, chapter)?.name == fileName && isSharedName(novel, chapter, fileName)) {
            return SaveResult.NAME_TAKEN
        }
        if (!provider.writeChapter(novel, chapter, selfContained, fileName)) return SaveResult.FAILED
        cache.addChapter(novel, chapter)
        return SaveResult.SAVED
    }

    private suspend fun isSharedName(novel: Novel, chapter: NovelChapter, fileName: String): Boolean =
        chapterRepo.getByNovelId(novel.id).any { it.id != chapter.id && provider.chapterFileName(it) == fileName }

    enum class SaveResult {
        SAVED,

        /** Another chapter of the same name already has the file, which stays its. */
        NAME_TAKEN,

        /** Nothing to save, or no storage to save it in. */
        FAILED,
    }
}
