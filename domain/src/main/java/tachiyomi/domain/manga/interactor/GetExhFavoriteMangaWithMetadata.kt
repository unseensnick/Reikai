package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import exh.source.eHentaiSourceIds
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

// favorited E-Hentai/ExHentai galleries that carry captured metadata, for the update checker.
@Inject
class GetExhFavoriteMangaWithMetadata(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(): List<Manga> {
        return mangaRepository.getExhFavoriteMangaWithMetadata(eHentaiSourceIds.toList())
    }
}
