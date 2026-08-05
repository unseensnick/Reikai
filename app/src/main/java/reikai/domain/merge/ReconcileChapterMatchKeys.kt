package reikai.domain.merge

import logcat.LogPriority
import reikai.domain.novel.NovelChapterAggregation
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager

/**
 * Brings the stored cross-source chapter identities back in step with the chapter rows, for both
 * content types, and is the only thing that writes those keys.
 * Reconciling from the difference rather than hooking each chapter-writing path is self-correcting
 * and covers paths that do not exist yet; a forgotten hook is a silently wrong unread badge. It
 * doubles as the one-time backfill, so no separate backfill code exists. Cheap when there is nothing
 * to do (one indexed query per content type), so callers may run it freely.
 */
class ReconcileChapterMatchKeys(
    private val repository: ChapterMatchKeyRepository,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
) {

    suspend fun await() {
        reconcileManga()
        reconcileNovels()
    }

    private suspend fun reconcileManga() {
        val stale = repository.getStaleMangaChapters()
        if (stale.isEmpty()) return

        // Gallery-ness is a property of the source, not the chapter, so resolve it once per manga
        // rather than per chapter: a merged entry commonly has hundreds of chapters.
        val galleryByMangaId = stale.map { it.mangaId }.distinct().associateWith { mangaId ->
            val sourceId = getManga.await(mangaId)?.source ?: return@associateWith false
            ChapterMatchKeys.isGallerySource(sourceId, sourceManager)
        }

        repository.upsertMangaKeys(
            stale.map { chapter ->
                ChapterMatchKeyRepository.ResolvedKey(
                    chapterId = chapter.chapterId,
                    matchKey = ChapterMatchKeys.manga(
                        chapterNumber = chapter.chapterNumber,
                        isGallerySource = galleryByMangaId[chapter.mangaId] == true,
                    ),
                    chapterNumber = chapter.chapterNumber,
                )
            },
        )
        logcat(LogPriority.DEBUG) { "Reconciled ${stale.size} manga chapter match keys" }
    }

    private suspend fun reconcileNovels() {
        val stale = repository.getStaleNovelChapters()
        if (stale.isEmpty()) return

        repository.upsertNovelKeys(
            stale.map { chapter ->
                ChapterMatchKeyRepository.ResolvedNovelKey(
                    chapterId = chapter.chapterId,
                    // Novels have no gallery concept; the title normaliser decides identity, and
                    // returns null when the chapter has none.
                    matchKey = NovelChapterAggregation.matchKey(chapter.name, chapter.chapterNumber),
                    name = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                )
            },
        )
        logcat(LogPriority.DEBUG) { "Reconciled ${stale.size} novel chapter match keys" }
    }
}
