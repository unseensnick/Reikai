package reikai.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.NovelCover

/** Twin of [eu.kanade.tachiyomi.data.coil.MangaCoverKeyer], pinned by [coverCacheKey]. */
class NovelCoverKeyer(private val coverCache: CoverCache) : Keyer<NovelCover> {
    override fun key(data: NovelCover, options: Options): String {
        // Only a library novel can have a custom cover, so one left behind by a removal is never served.
        val owner = EntryId.Novel(data.novelId).takeIf { data.isNovelFavorite && coverCache.hasCustomCover(it) }
        return coverCacheKey(owner, data.url, data.lastModified)
    }
}
