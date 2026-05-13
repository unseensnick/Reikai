package eu.kanade.tachiyomi.ui.manga.related.browse

import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 6.5 — process-scoped pool handoff between the manga details screen and the new
 * full-screen "See all" browse view.
 *
 * Why in-memory and not Bundle: [eu.kanade.tachiyomi.source.model.SManga] extends
 * `java.io.Serializable` and lives in the `source/api` plugin contract, so we can neither
 * mark it `@Parcelize` (public-surface change forbidden) nor pay the cost of Serializable
 * round-tripping ~150 entries through the Conductor saved-state bundle. The browse
 * controller's Bundle carries only `mangaId: Long`; the candidate list comes from here.
 *
 * Snapshot semantics: the writer ([eu.kanade.tachiyomi.ui.manga.MangaDetailsController]
 * on the "See all" tap) deposits the current full pool; the reader (the browse controller's
 * `onAttach`) takes-and-clears. If process death wipes the map between deposit and take,
 * the browse controller routes back to the parent details screen and the user retaps.
 *
 * Singleton (Koin), one instance per process. No persistence — restart wipes the map.
 */
class RelatedMangasHandoff {

    private val entries = ConcurrentHashMap<Long, List<RelatedMangaCandidate>>()

    fun put(mangaId: Long, pool: List<RelatedMangaCandidate>) {
        entries[mangaId] = pool
    }

    /** Read-and-clear. Returns null if no pool was deposited (or it's already been claimed). */
    fun take(mangaId: Long): List<RelatedMangaCandidate>? = entries.remove(mangaId)
}
