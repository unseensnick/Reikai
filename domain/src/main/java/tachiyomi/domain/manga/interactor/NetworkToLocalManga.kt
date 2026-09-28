package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.source.healedCover
import reikai.domain.source.keptCover
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaRemoteUpdate
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class NetworkToLocalManga(
    private val mangaRepository: MangaRepository,
) {

    suspend operator fun invoke(manga: Manga): Manga {
        return invoke(listOf(manga)).single()
    }

    suspend operator fun invoke(manga: List<Manga>): List<Manga> {
        // RK --> a listing placeholder never replaces a cover, and a real one repairs a favourite stuck without one
        val listed = manga.map { it.copy(thumbnailUrl = keptCover(null, it.thumbnailUrl)) }
        return mangaRepository.insertNetworkManga(listed).mapIndexed { i, stored ->
            val cover = healedCover(stored.thumbnailUrl, listed[i].thumbnailUrl)
            if (cover != null) {
                // The cover is the source's, so it goes through the source-details write, which keeps
                // every field it is handed null for and rewrites the rest with the row just read.
                mangaRepository.updateRemote(
                    MangaRemoteUpdate(
                        id = stored.id,
                        title = null,
                        author = null,
                        artist = null,
                        description = null,
                        genre = null,
                        status = stored.status,
                        thumbnailUrl = cover,
                        updateStrategy = stored.updateStrategy,
                        memo = stored.memo,
                        initialized = stored.initialized,
                        coverLastModified = null,
                    ),
                )
                stored.copy(thumbnailUrl = cover)
            } else {
                stored
            }
        }
        // RK <--
    }
}
