package eu.kanade.tachiyomi.ui.manga.related.browse

import eu.kanade.tachiyomi.data.database.models.copyFrom
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga

/**
 * Phase 6.5 — resolves an [SManga] to a local [Manga] DB row, creating one if missing.
 *
 * Mirrors `MangaDetailsPresenter.toLocalManga` so a tap from the browse view opens a manga in
 * the same shape as a tap from the carousel. Kept stand-alone (no presenter dependency) so the
 * browse controller doesn't have to reach across screen lifetimes for resolution.
 */
internal object MangaResolver {

    suspend fun resolve(sourceId: Long, sManga: SManga): Manga? {
        val getManga: GetManga = Injekt.get()
        val insertManga: InsertManga = Injekt.get()

        var localManga = getManga.awaitByUrlAndSource(sManga.url, sourceId)
        if (localManga == null) {
            val newManga = try {
                Manga.create(sManga.url, sManga.title, sourceId)
            } catch (_: UninitializedPropertyAccessException) {
                return null
            }
            newManga.copyFrom(sManga)
            newManga.id = insertManga.await(newManga)
            localManga = newManga
        } else if (!localManga.favorite) {
            localManga.title = try {
                sManga.title
            } catch (_: UninitializedPropertyAccessException) {
                return localManga
            }
        }
        return localManga
    }
}
