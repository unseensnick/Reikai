package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.entry.restoredAltTitles
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

@Inject
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

    /** A backup's row over the device's, by [restoredAltTitles], pinned by BackupCustomInfoConformanceTest. */
    suspend fun restore(info: CustomMangaInfo) {
        set(info.copy(altTitles = restoredAltTitles(info, repository.getByMangaId(info.mangaId))))
    }
}
