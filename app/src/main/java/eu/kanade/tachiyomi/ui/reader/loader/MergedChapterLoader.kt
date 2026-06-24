package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * RK: merge-aware loader. Holds one Mihon [ChapterLoader] per merged source, keyed by the chapter's
 * own manga id, so a single reader session can load pages across the whole merge group. Each
 * delegate loader is built with that source's own manga + source, so Mihon's [ChapterLoader] and its
 * page loaders (download path, http) resolve correctly with no changes to them.
 *
 * When the manga is not merged this holds a single loader and behaves exactly like [ChapterLoader].
 */
class MergedChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val mangaById: Map<Long, Manga>,
    private val sourceManager: SourceManager = Injekt.get(),
) {

    private val loaders = HashMap<Long, ChapterLoader>()

    suspend fun loadChapter(chapter: ReaderChapter) {
        loaderFor(chapter.chapter.manga_id!!).loadChapter(chapter)
    }

    private fun loaderFor(mangaId: Long): ChapterLoader = loaders.getOrPut(mangaId) {
        val manga = mangaById.getValue(mangaId)
        ChapterLoader(context, downloadManager, downloadProvider, manga, sourceManager.getOrStub(manga.source))
    }
}
