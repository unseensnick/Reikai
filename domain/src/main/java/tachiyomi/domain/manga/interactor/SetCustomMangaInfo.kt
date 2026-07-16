package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

class SetCustomMangaInfo(
    private val repository: CustomMangaInfoRepository,
) {

    /**
     * Persist the override, or clear it when nothing is set (Reset to source). Suspends until the write
     * lands so an edit survives an immediate process death; the observing flow then re-emits and the
     * screen updates on its own (no manual state poke, no `mangas`-row bump).
     */
    suspend fun set(info: CustomMangaInfo) {
        if (info.isEmpty) repository.delete(info.mangaId) else repository.set(info)
    }
}
