package yokai.presentation.library

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import kotlinx.coroutines.flow.Flow

/**
 * Compose-side wrapper over the legacy `LibraryUpdateJob` companion API so the Compose library
 * can drive refreshes, observe running state, and check per-category queue membership without
 * pulling [android.content.Context] through the screen layer.
 *
 * Manga and novel tabs each ship their own implementation (Phase 4: [yokai.presentation.library.manga.MangaLibraryUpdater];
 * Phase 7 adds the novel side).
 */
interface LibraryUpdater {

    /**
     * Per-manga progress signal. Emits a manga id as each entry finishes updating, the
     * `STARTING_UPDATE_SOURCE` sentinel (-5L) at job start, and null on completion. The Compose
     * side mainly uses the null emission to kick a re-derivation of in-queue category state;
     * library row data refreshes via the existing `getLibraryManga.subscribe()` flow.
     */
    val updateFlow: Flow<Long?>

    /**
     * Enqueue an update for [category] (or all categories when null). Returns true if the job
     * was newly started, false if it was already running (in which case [category] is added to
     * the existing job's queue). The caller branches snackbar wording on the result.
     *
     * For dynamic categories (synthetic, negative ids — BY_SOURCE/BY_LANGUAGE/BY_TAG/etc.) the
     * caller MUST pass [mangaToUse] with the bucket's manga list. The legacy worker can't
     * resolve a synthetic id back to a DB filter, so without [mangaToUse] the worker would do
     * nothing. Mirrors `LibraryController.updateCategory` at LibraryController.kt:1806-1815.
     */
    fun startNow(category: Category? = null, mangaToUse: List<LibraryManga>? = null): Boolean

    fun stop()

    fun isRunning(): Boolean

    fun isRunningFlow(): Flow<Boolean>

    /** Whether [categoryId] is in the currently-running job's queue. */
    fun isCategoryInQueue(categoryId: Int?): Boolean
}
