package reikai.domain.manga

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import reikai.domain.library.ContentType
import reikai.domain.library.EntryLibraryRemoval
import reikai.domain.local.LocalVolumeHandBack
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Instant

/** [EntryLibraryRemoval] over the manga table. Writes only the library date. */
@Inject
class RemoveMangaFromLibrary(
    mergeManager: MangaMergeManager,
    sourceTracker: SourceTrackerDispatcher,
    private val getManga: GetManga,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    localVolumes: () -> LocalVolumeHandBack,
) : EntryLibraryRemoval(mergeManager, sourceTracker, localVolumes) {

    override val contentType = ContentType.MANGA

    override suspend fun favoritesAt(ids: List<Long>): Map<Long, Instant> =
        getManga.awaitAll(ids).mapNotNull { manga -> manga.favoriteAt?.let { manga.id to it } }.toMap()

    override suspend fun writeFavoriteAt(favoriteAt: Map<Long, Instant?>): Boolean =
        updateManga.awaitAll(favoriteAt.map { (id, at) -> MangaUpdate(id) { this.favoriteAt = at } })

    override suspend fun deleteCovers(ids: List<Long>) {
        getManga.awaitAll(ids).forEach { it.removeCovers(coverCache) }
    }
}
