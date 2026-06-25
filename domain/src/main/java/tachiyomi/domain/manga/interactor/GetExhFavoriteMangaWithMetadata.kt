package tachiyomi.domain.manga.interactor

import exh.source.eHentaiSourceIds
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

// RK: favorited E-Hentai/ExHentai galleries that carry captured metadata, for the update checker.
class GetExhFavoriteMangaWithMetadata(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(): List<Manga> {
        return mangaRepository.getExhFavoriteMangaWithMetadata(eHentaiSourceIds.toList())
    }
}
