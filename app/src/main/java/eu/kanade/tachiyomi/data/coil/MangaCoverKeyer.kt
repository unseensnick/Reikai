package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import reikai.data.coil.coverCacheKey
import reikai.data.coil.libraryCustomCoverOwner
import reikai.domain.entry.EntryId
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.Manga as DomainManga

class MangaKeyer : Keyer<DomainManga> {
    override fun key(data: DomainManga, options: Options): String {
        // RK --> one key rule for manga and novel covers (coverCacheKey); the strings are upstream's
        val owner = libraryCustomCoverOwner(EntryId.Manga(data.id), data.favorite) { data.hasCustomCover() }
        return coverCacheKey(owner, data.thumbnailUrl, data.coverLastModified)
        // RK <--
    }
}

class MangaCoverKeyer(
    private val coverCache: CoverCache,
) : Keyer<MangaCover> {
    override fun key(data: MangaCover, options: Options): String {
        // RK --> one key rule for manga and novel covers (coverCacheKey); the strings are upstream's
        val owner =
            libraryCustomCoverOwner(EntryId.Manga(data.mangaId), data.isMangaFavorite, coverCache::hasCustomCover)
        return coverCacheKey(owner, data.url, data.lastModified)
        // RK <--
    }
}
