package reikai.domain.migration

import dev.zacsweers.metro.Inject
import reikai.domain.entry.EntryId
import reikai.domain.library.libraryMembershipChanged
import reikai.domain.manga.MangaGroupCategories
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelGroupCategories
import reikai.domain.novel.NovelMergeManager
import reikai.domain.track.source.SourceTrackerDispatcher

/**
 * What both migrate engines do once their favourite swap has committed, outside its transaction so
 * none of it can roll the swap back: file the target's categories, move both entries' cover colours
 * to their new library state (a colour moves store only on a cover load otherwise, and a replaced
 * entry may never load again), then tell the source's own tracker.
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
    ) {
        when (to) {
            is EntryId.Manga -> mangaCategories.fileMigrationTarget(to.rawId, categoryIds, mangaMerge)
            is EntryId.Novel -> novelCategories.fileMigrationTarget(to.rawId, categoryIds, novelMerge)
        }
        if (replace) sourceTracker.libraryMembershipChanged(from, inLibrary = false, tellSource = false)
        sourceTracker.libraryMembershipChanged(to, inLibrary = true, tellSource = false)
        sourceTracker.migrated(from, to, replace, carriedChapters)
    }
}
