package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.source.healedCover
import reikai.domain.source.keptCover
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
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
                mangaRepository.update(MangaUpdate(id = stored.id, thumbnailUrl = cover))
                stored.copy(thumbnailUrl = cover)
            } else {
                stored
            }
        }
        // RK <--
    }
}
