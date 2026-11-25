package reikai.domain.migration

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.domain.entry.EntryId
import reikai.domain.library.libraryMembershipChanged
import reikai.domain.manga.MangaGroupCategories
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelGroupCategories
import reikai.domain.novel.NovelMergeManager
import reikai.domain.track.source.SourceTrackerDispatcher
import tachiyomi.core.common.util.system.logcat

/**
 * What both migrate engines do once their favourite swap has committed, outside its transaction so
 * none of it can roll the swap back: file the target's categories, move both entries' cover colours
 * to their new library state (a colour moves store only on a cover load otherwise, and a replaced
 * entry may never load again), then tell the source's own tracker. The swap already happened, so a
 * Stop does not cut these short and a failed step is logged rather than failing the row, whose Retry
 * would migrate the entry again.
 */
@Inject
class MigrationSwapEffects(
    private val mangaCategories: MangaGroupCategories,
    private val novelCategories: NovelGroupCategories,
    private val mangaMerge: MangaMergeManager,
    private val novelMerge: NovelMergeManager,
    private val sourceTracker: SourceTrackerDispatcher,
) {

    /** [categoryIds] is what the category option carried, or null without it. */
    suspend fun afterSwap(
        from: EntryId,
        to: EntryId,
        replace: Boolean,
        categoryIds: List<Long>?,
        carriedChapters: Boolean,
    ) = withContext(NonCancellable) {
        step("category filing") {
            when (to) {
                is EntryId.Manga -> mangaCategories.fileMigrationTarget(to.rawId, categoryIds, mangaMerge)
                is EntryId.Novel -> novelCategories.fileMigrationTarget(to.rawId, categoryIds, novelMerge)
            }
        }
        step("library state") {
            if (replace) sourceTracker.libraryMembershipChanged(from, inLibrary = false, tellSource = false)
            sourceTracker.libraryMembershipChanged(to, inLibrary = true, tellSource = false)
        }
        step("source tracker") { sourceTracker.migrated(from, to, replace, carriedChapters) }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Migration $name failed after the swap committed" }
        }
    }
}
