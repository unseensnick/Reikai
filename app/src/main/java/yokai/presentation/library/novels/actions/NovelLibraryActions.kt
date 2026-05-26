package yokai.presentation.library.novels.actions

import co.touchlab.kermit.Logger
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.novel.models.Novel
import yokai.domain.novel.models.NovelChapter
import yokai.novel.source.NovelSourceManager

/**
 * Novel-side parallel of [yokai.presentation.library.manga.actions.MangaLibraryActions]. Pure
 * suspend functions wired through by the screen model; no Injekt / Koin imports here, so the
 * screen model passes interactors / repos in.
 *
 * Diverges from the manga template in four places, all locked by Phase 7 decisions:
 *
 * - **[downloadUnread] is a no-op stub** (Decision #4): novel downloads ship in a later phase.
 *   Kept on the call surface so [yokai.presentation.library.novels.NovelLibraryScreenModel] can
 *   wire it symmetrically in C32; the no-op makes any UI binding harmless until real downloads
 *   land.
 * - **[confirmDeletion] calls [NovelTrackRepository.deleteAllForNovel] directly** instead of
 *   going through a `DeleteTrack`-style interactor (no novel parallel exists), and skips the
 *   manga side's `coverCache.removeCover` + `downloadManager.deleteManga` + tracker-cache
 *   invalidation (Decision #5 + no novel CoverCache + Decision #4).
 * - **Merge / unmerge actions move to [yokai.presentation.library.novels.actions.NovelLibraryActions]
 *   in C31**; they're not in this file yet to keep C30's diff scoped.
 * - **`reconcileGroupTrackers` + `propagateTracksAcrossGroup` dropped entirely** (Decision #5).
 *
 * Library refresh is not poked manually here: the Compose screen model collects
 * [NovelRepository.getLibraryNovelAsFlow] which re-emits on chapter writes, so the grid
 * updates naturally after each action completes.
 */
object NovelLibraryActions {

    /**
     * Resolve source URLs for sharing. Concatenates the source's site root with the novel's
     * source-relative path. Sources without a parsable site (or absent from the registry) are
     * silently filtered out, matching manga's `as? HttpSource ?: return@mapNotNull null` guard.
     */
    fun share(novels: List<Novel>, novelSourceManager: NovelSourceManager): List<String> =
        novels.mapNotNull { novel ->
            val source = novelSourceManager.get(novel.source) ?: return@mapNotNull null
            val site = source.site.trimEnd('/')
            if (site.isBlank()) return@mapNotNull null
            site + novel.url
        }

    /**
     * Stub. Real novel downloads are deferred per Phase 7 Decision #4; this function exists so
     * the screen model can wire `downloadUnreadSelection()` in C32 symmetrically to manga.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun downloadUnread(novels: List<Novel>) {
        Logger.d("Novel downloads deferred (Phase 7 Decision #4) — selection ignored")
    }

    /**
     * Apply read / unread status to every chapter of every selected novel. Returns a snapshot of
     * the original chapter list per novel so [undoMarkReadStatus] can restore exact pre-update
     * state (including [NovelChapter.lastTextProgress]), matching manga's semantics.
     *
     * Updates one chapter at a time (the [NovelChapterRepository] interface has no bulk method
     * yet); each `update()` wraps in its own transaction. If this becomes a hot path for large
     * libraries, add a bulk-update method on the repo and switch the call here.
     */
    suspend fun markReadStatus(
        novels: List<Novel>,
        markRead: Boolean,
        novelChapterRepository: NovelChapterRepository,
    ): Map<Novel, List<NovelChapter>> {
        val snapshot = LinkedHashMap<Novel, List<NovelChapter>>()
        novels.forEach { novel ->
            val novelId = novel.id ?: return@forEach
            val chapters = novelChapterRepository.getByNovelId(novelId)
            chapters.forEach { ch ->
                novelChapterRepository.update(ch.copy(read = markRead, lastTextProgress = 0))
            }
            snapshot[novel] = chapters
        }
        return snapshot
    }

    suspend fun undoMarkReadStatus(
        snapshot: Map<Novel, List<NovelChapter>>,
        novelChapterRepository: NovelChapterRepository,
    ) {
        snapshot.values.flatten().forEach { ch ->
            novelChapterRepository.update(ch)
        }
    }

    /**
     * Post-snackbar cleanup hook. Manga deletes downloaded chapters here when the user opted
     * into `removeAfterMarkedAsRead`; novels stub it because downloads aren't wired (Decision
     * #4). Kept on the call surface so the screen model wiring reads symmetrically; the body
     * gets a real implementation when novel downloads land.
     */
    @Suppress("UNUSED_PARAMETER")
    fun confirmMarkReadStatus(
        snapshot: Map<Novel, List<NovelChapter>>,
        markRead: Boolean,
        removeAfterMarkedAsRead: Boolean,
    ) {
        // No-op until novel downloads ship.
    }

    /**
     * Mark every selected novel `favorite = false`. Reversible via [reAddToLibrary] while the
     * undo snackbar is alive; destructive cleanup runs in [confirmDeletion] on snackbar dismiss.
     */
    suspend fun removeFromLibrary(novels: List<Novel>, novelRepository: NovelRepository) {
        novels.distinctBy { it.id }.forEach { novel ->
            novelRepository.update(novel.copy(favorite = false))
        }
    }

    /** Undo of [removeFromLibrary]: flip favorite back to true. */
    suspend fun reAddToLibrary(novels: List<Novel>, novelRepository: NovelRepository) {
        novels.distinctBy { it.id }.forEach { novel ->
            novelRepository.update(novel.copy(favorite = true))
        }
    }

    /**
     * Destructive cleanup on snackbar dismissal. Calls
     * [NovelTrackRepository.deleteAllForNovel] for each novel per Decision #5 (no novel
     * `DeleteTrack` interactor exists). Cover-cache invalidation, download deletion, and
     * tracker-reconciliation-cache invalidation all dropped — none have novel parallels yet.
     */
    suspend fun confirmDeletion(
        novels: List<Novel>,
        novelTrackRepository: NovelTrackRepository,
    ) {
        novels.distinctBy { it.id }.forEach { novel ->
            novel.id?.let { novelTrackRepository.deleteAllForNovel(it) }
        }
    }

    /**
     * Manual merge. Verbatim port of [yokai.presentation.library.manga.actions.MangaLibraryActions.merge]
     * with `mangaManualMerges` / `mangaManualUnmerges` swapped for the C23 novel keys.
     *
     * Sorts ids, drops any existing merge entries that mention any of these ids (collision
     * avoidance), inserts the new entry, and strips only the unmerge pairs wholly contained
     * in the new merge set so an earlier full-group unmerge isn't silently undone. Returns
     * the sorted ids. Requires `ids.size >= 2`.
     *
     * Decision #5: no `reconcileGroupTrackers` call on the novel side; the caller in C32
     * doesn't conditionally invoke any tracker reconciliation. The return value is kept on
     * the surface so the screen model can use it for snackbar text / undo state.
     */
    fun merge(ids: List<Long>, novelPreferences: NovelPreferences): List<Long> {
        val sorted = ids.sorted()
        val sortedSet = sorted.toSet()
        val newEntry = sorted.joinToString(",")

        val merges = novelPreferences.novelManualMerges().get().toMutableSet()
        merges.removeAll { entry ->
            entry.split(",").any { part -> part.trim().toLongOrNull() in sortedSet }
        }
        merges.add(newEntry)
        novelPreferences.novelManualMerges().set(merges)

        val unmerges = novelPreferences.novelManualUnmerges().get().toMutableSet()
        unmerges.removeAll { entry ->
            val parts = entry.split(",").mapNotNull { it.trim().toLongOrNull() }
            parts.isNotEmpty() && parts.all { it in sortedSet }
        }
        novelPreferences.novelManualUnmerges().set(unmerges)

        return sorted
    }

    /**
     * Library multi-select unmerge. Verbatim port of
     * [yokai.presentation.library.manga.actions.MangaLibraryActions.unmerge].
     *
     * Wholesale-dissolve semantics: picking one member of a merged group is enough; the whole
     * group breaks apart, and every pair within the original group is recorded in
     * `novelManualUnmerges` so the same-title auto-grouping pass in [NovelLibraryGrouping]
     * (C21) cannot re-form the group. Pair format `"smallerId,largerId"` matches what
     * [NovelLibraryGrouping] consumes. Entries with no target overlap are passed through
     * unchanged.
     */
    fun unmerge(targetIds: List<Long>, novelPreferences: NovelPreferences) {
        if (targetIds.isEmpty()) return
        val targetSet = targetIds.toSet()

        val originalMerges = novelPreferences.novelManualMerges().get()
        val updatedMerges = LinkedHashSet<String>()
        val unmerges = novelPreferences.novelManualUnmerges().get().toMutableSet()

        for (entry in originalMerges) {
            val members = entry.split(",").mapNotNull { it.trim().toLongOrNull() }.distinct()
            val anyTargetInGroup = members.any { it in targetSet }
            if (!anyTargetInGroup) {
                updatedMerges.add(entry)
                continue
            }
            for (i in members.indices) {
                for (j in (i + 1) until members.size) {
                    val a = members[i]
                    val b = members[j]
                    val pair = if (a < b) "$a,$b" else "$b,$a"
                    unmerges.add(pair)
                }
            }
        }

        novelPreferences.novelManualMerges().set(updatedMerges)
        novelPreferences.novelManualUnmerges().set(unmerges)
    }
}
