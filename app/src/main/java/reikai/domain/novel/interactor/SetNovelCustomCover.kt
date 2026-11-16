package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.local.LocalNovelSource
import java.io.InputStream

/** Writes [stream] as a novel's own cover, what the cover viewer's Edit and the reader's Set as cover run. */
@Inject
class SetNovelCustomCover(
    private val novelRepo: NovelRepository,
    private val updateNovel: UpdateNovel,
    private val coverCache: CoverCache,
    private val localNovelSource: LocalNovelSource,
) {
    suspend fun await(novel: Novel, stream: InputStream) {
        val isLocal = LocalNovelSource.isLocal(novel.source)
        val inFolder = if (isLocal) localNovelSource.writeCover(novel.url, stream) else null
        // The stored address may be the book's own picture, copied out, which the folder's cover now replaces.
        if (inFolder != null) {
            novelRepo.update(NovelUpdate(novel.id) { thumbnailUrl = inFolder })
        } else {
            coverCache.setCustomCoverToCache(EntryId.Novel(novel.id), stream)
        }
        updateNovel.awaitUpdateCoverLastModified(novel.id)
    }

    companion object {
        /** As manga's: a local novel's cover goes into its own folder, so it needs no library entry to keep it. */
        fun canSet(novel: Novel): Boolean = novel.favorite || LocalNovelSource.isLocal(novel.source)
    }
}
