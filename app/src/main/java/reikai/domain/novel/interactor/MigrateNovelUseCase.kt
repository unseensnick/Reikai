package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.category.GetNovelCategories
import reikai.domain.db.Transactions
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.hasCustomCover
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Clock

/**
 * Move a favorited novel's state onto a [target] novel from another source, the novel twin of
 * [mihon.domain.migration.usecases.MigrateMangaUseCase]. The target is already materialised and
 * chapter-synced by the picker, so this is mostly DB work: per-chapter read, bookmark and progress
 * matched by chapter number, categories, the custom cover and notes when their flags are set,
 * favoriting, tracker links re-pointed to the target, and the merge group kept consistent (the target
 * takes the source's place on [replace], or joins it on copy). History is not carried, matching Mihon.
 */
@Inject
class MigrateNovelUseCase(
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val getNovelCategories: GetNovelCategories = Injekt.get(),
    private val setNovelCategories: SetNovelCategories = Injekt.get(),
    private val novelMergeManager: NovelMergeManager = Injekt.get(),
    private val novelDownloadManager: NovelDownloadManager = Injekt.get(),
    private val updateNovel: UpdateNovel = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getNovelTracks: GetNovelTracks = Injekt.get(),
    private val insertNovelTrack: InsertNovelTrack = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val database: Database = Injekt.get(),
    // So the favorite swap and the merge-group rewrite can share one transaction; see below.
    private val transactions: Transactions = Injekt.get(),
) {

    suspend operator fun invoke(
        current: Novel,
        target: Novel,
        flags: Set<NovelMigrationFlag>,
        replace: Boolean,
        /** The caller just fetched the target's chapters, so the refresh below would repeat the
         *  identical request; a novel refresh also walks every chapter page, so it is not cheap. */
        skipTargetRefresh: Boolean = false,
    ) {
        if (current.id == target.id) return
        try {
            // Checked before anything is written, and outside the refresh branch below, so the engine
            // fails the same way manga's does whether or not the caller pre-fetched. It used to sit
            // inside that branch, which is skipped on the normal commit path, so the engine would
            // happily migrate onto a source it could not resolve and leave the entry unreadable.
            val targetSource = checkNotNull(sourceManager.get(target.source)) {
                "Target source ${target.source} unavailable"
            }
            // Capture the source's merge group up front, before the target is favorited, so it's the
            // source plus its existing siblings, not the target (which shares the title on a clean match).
            val group = novelMergeManager.computeRelatedIds(current.id)

            // Fetch the target's chapters from its source first, so read progress can match onto them
            // (parity with MigrateMangaUseCase.updateMangaFromRemote). This lets the migrate work from any
            // add-path, including browse / global search where the target is a fresh, unsynced row.
            // A refresh failure fails the row, matching manga's getOrThrow contract: migrating onto a
            // target whose chapter list could not be brought up to date would carry read state onto
            // whatever stale rows happen to be there. The row's retry is the recovery path.
            if (!skipTargetRefresh) {
                refreshNovelFromSource(
                    target,
                    targetSource,
                    novelChapterRepository,
                    novelRepository,
                    database,
                    novelDownloadManager,
                )
            }

            if (NovelMigrationFlag.CHAPTER in flags) {
                val currentChapters = novelChapterRepository.getByNovelId(current.id)
                val targetChapters = novelChapterRepository.getByNovelId(target.id)
                val carried = computeChapterMigration(currentChapters, targetChapters)
                // One transaction, the twin of the manga carry's repository write, and checked: a
                // half-carried read state is exactly what the Failed row + retry exist to prevent.
                if (carried.isNotEmpty()) {
                    check(novelChapterRepository.updateAll(carried)) {
                        "Chapter-state carry failed (${current.id} -> ${target.id})"
                    }
                }
            }

            if (NovelMigrationFlag.CATEGORY in flags) {
                val categoryIds = getNovelCategories.awaitByNovelId(current.id).map { it.id }
                setNovelCategories.await(target.id, categoryIds)
            }

            // Carry tracker links onto the target, re-pointed to its id (matching manga migration). The
            // source's own track rows are left intact, which is correct for a Copy.
            insertNovelTrack.awaitAll(
                getNovelTracks.await(current.id).map { it.copy(novelId = target.id) },
            )

            // Delete the old source's downloaded chapters (parity with manga's REMOVE_DOWNLOAD). The
            // file delete is a no-op when nothing is downloaded. Downloads are never auto re-fetched
            // onto the target: parity with manga, and a silent re-download costs metered data.
            if (NovelMigrationFlag.REMOVE_DOWNLOAD in flags) {
                // The whole entry, not its downloaded chapters: filtering by the disk cache misses
                // everything still queued, which then keeps downloading into the source being left,
                // and misses everything on disk when the cache has not warmed up yet. Awaited, unlike
                // manga's detached delete, so a failure can fail the row.
                novelDownloadManager.awaitDeleteNovel(current)
            }

            if (NovelMigrationFlag.COVER in flags && current.hasCustomCover(coverCache)) {
                coverCache.getCustomCoverFile(EntryId.Novel(current.id)).inputStream().use { input ->
                    coverCache.getCustomCoverFile(EntryId.Novel(target.id))
                        .outputStream().use { output -> input.copyTo(output) }
                }
                // Bump the target's coverLastModified so coil reloads, mirroring NovelCoverViewModel.
                updateNovel.awaitUpdateCoverLastModified(target.id)
            }

            // The favorite swap and the merge-group rewrite are ONE unit of work, with the swap
            // genuinely last inside it, matching manga. They used to be two transactions with a
            // suspension point between them: a batch the user cancelled could commit the swap and
            // never reach the rewrite, leaving the source out of the library but still in the group,
            // feeding chapters into it while invisible there and unreachable to unmerge. Everything
            // above touches only satellite state, so a failure there leaves both entries' library
            // membership untouched.
            val currentUpdate = NovelUpdate(
                id = current.id,
                favorite = false,
                // dateAdded zeroes like manga migration, so a later re-add stamps fresh instead of
                // inheriting the pre-migration date.
                dateAdded = 0,
            ).takeIf { replace }
            val targetUpdate = NovelUpdate(
                id = target.id,
                favorite = true,
                // Inherit the source's added-date on a replace, else stamp now, matching manga
                // migration; this favorite path bypasses awaitUpdateFavorite, which is the only
                // other place dateAdded is set, so without this a migrated novel sorts to epoch 0.
                dateAdded = if (replace) current.dateAdded else Clock.System.now().toEpochMilliseconds(),
                // Carry the chapter-list (sort/filter/display) and reader (orientation) flags onto
                // the target unconditionally, matching manga migration.
                chapterFlags = current.chapterFlags,
                viewerFlags = current.viewerFlags,
                lastReadAt = current.lastReadAt ?: target.lastReadAt,
                notes = if (NovelMigrationFlag.NOTES in flags) current.notes else null,
            )
            transactions.run {
                // Keep the merge consistent: the target takes the source's place in the group on a
                // replace, or joins it on a copy.
                if (replace) {
                    novelMergeManager.replaceInGroup(current.id, target.id)
                } else if (group.size > 1) {
                    novelMergeManager.merge(group.toList() + target.id)
                }
                // The repository swallows into a Boolean; a false MUST fail the row, or a half swap
                // could drop the entry from the library with the row reading Migrated. Throwing here
                // rolls the group rewrite back with it, which is the point of the shared transaction.
                check(novelRepository.updateAll(listOfNotNull(currentUpdate, targetUpdate))) {
                    "Migration favorite swap failed (${current.id} -> ${target.id})"
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Rethrown after logging, matching manga migration: a caller that shows per-row outcomes
            // has to be able to tell a failure from a success.
            logcat(LogPriority.ERROR, e) { "Novel migration failed (${current.id} -> ${target.id})" }
            throw e
        }
    }
}

/**
 * Pure core: given the source novel's chapters and the target's, return the target chapters whose
 * read, bookmark, progress or dateFetch should change. A target chapter takes its matched (same
 * number) source chapter's state, and every target chapter at or below the highest read source number
 * is marked read, mirroring Mihon's `maxChapterRead` sweep. Unrecognized numbers are skipped.
 * PROGRESS ONLY EVER RISES: read and position are merged, never overwritten, so migrating onto a
 * target already in the library cannot un-read it. [NovelChapter.bookmark] is deliberately NOT merged.
 */
internal fun computeChapterMigration(
    currentChapters: List<NovelChapter>,
    targetChapters: List<NovelChapter>,
): List<NovelChapter> {
    val maxReadNumber = currentChapters
        .filter { it.read && it.chapterNumber >= 0.0 }
        .maxOfOrNull { it.chapterNumber }

    return targetChapters.mapNotNull { target ->
        if (target.chapterNumber < 0.0) return@mapNotNull null

        val match = currentChapters.firstOrNull { it.chapterNumber >= 0.0 && it.chapterNumber == target.chapterNumber }
        var read = target.read || match?.read == true
        val bookmark = if (match != null) match.bookmark else target.bookmark
        val progress = maxOf(target.lastTextProgress, match?.lastTextProgress ?: 0L)
        // dateFetch carries too (manga parity): without it every migrated chapter reads as
        // freshly fetched and floods recency-ordered surfaces.
        val dateFetch = if (match != null) match.dateFetch else target.dateFetch
        if (maxReadNumber != null && target.chapterNumber <= maxReadNumber) read = true

        if (read == target.read && bookmark == target.bookmark && progress == target.lastTextProgress &&
            dateFetch == target.dateFetch
        ) {
            null
        } else {
            target.copy(read = read, bookmark = bookmark, lastTextProgress = progress, dateFetch = dateFetch)
        }
    }
}
