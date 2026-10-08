package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.PagePreview
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.Source
import exh.source.getMainSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

@Inject
class GetPagePreviews(
    private val pagePreviewCache: PagePreviewCache,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    /**
     * The cached or fetched page list, then the list fetched again once if a dead image drops it from the
     * cache, so a strip built from expired links fills in without reopening the entry. One refetch only:
     * a list whose fresh links still fail would otherwise be fetched in a loop.
     */
    fun subscribe(manga: Manga, source: Source, page: Int): Flow<Result> = flow {
        val previewSource = source.getMainSource<PagePreviewSource>()
        if (previewSource == null) {
            emit(Result.Unused)
            return@flow
        }
        val chapters = getChaptersByMangaId.await(manga.id).sortedByDescending { it.sourceOrder }
        val pageListKey = pagePreviewCache.pageListKey(manga, chapters.map { it.id }, page)
        pagePreviewCache.removedPageLists
            // Loads only once subscribed, so a drop that lands while the first list is still loading is seen.
            .onSubscription { emit(pageListKey) }
            .filter { it == pageListKey }
            .take(2)
            .map { load(previewSource, manga, chapters, page, pageListKey) }
            .collect(::emit)
    }

    private suspend fun load(
        source: PagePreviewSource,
        manga: Manga,
        chapters: List<Chapter>,
        page: Int,
        pageListKey: String,
    ): Result {
        val chapterIds = chapters.map { it.id }
        return try {
            val pagePreviews = try {
                pagePreviewCache.getPageListFromCache(manga, chapterIds, page)
            } catch (_: Exception) {
                source.getPagePreviewList(manga.toSManga(), chapters.map { it.toSChapter() }, page).also {
                    pagePreviewCache.putPageListToCache(manga, chapterIds, it)
                }
            }
            Result.Success(
                pagePreviews.pagePreviews.map {
                    PagePreview(it.index, it.imageUrl, source.id, pageListKey)
                },
                pagePreviews.hasNextPage,
                pagePreviews.pagePreviewPages,
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    sealed class Result {
        data object Unused : Result()
        data class Success(
            val pagePreviews: List<PagePreview>,
            val hasNextPage: Boolean,
            val pageCount: Int?,
        ) : Result()
        data class Error(val error: Throwable) : Result()
    }
}
