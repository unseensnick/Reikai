package reikai.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.NovelCover

/** Twin of [eu.kanade.tachiyomi.data.coil.MangaCoverKeyer], pinned by [coverCacheKey]. */
class NovelCoverKeyer(private val coverCache: CoverCache) : Keyer<NovelCover> {
    override fun key(data: NovelCover, options: Options): String {
        // A source result with no stored row passes novelId 0, whose custom-cover file never exists.
        val owner = EntryId.Novel(data.novelId).takeIf { coverCache.getCustomCoverFile(it).exists() }
        return coverCacheKey(owner, data.url, data.lastModified)
    }
}
