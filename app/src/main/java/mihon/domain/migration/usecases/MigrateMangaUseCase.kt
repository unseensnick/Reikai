package mihon.domain.migration.usecases

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import reikai.domain.db.Transactions
import reikai.domain.manga.MangaMergeManager
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Clock

@Inject
class MigrateMangaUseCase(
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    // RK: defaulted so DomainModule's positional factory stays unchanged; keeps migration merge-aware.
    private val mangaMergeManager: MangaMergeManager = Injekt.get(),
    // RK: the repository, not UpdateChapter, because that interactor logs and swallows; the carry
    // has to be able to fail the row. Same reason the novel engine checks its own carry.
    private val chapterRepository: ChapterRepository = Injekt.get(),
    // RK: so the favorite swap and the merge-group rewrite can share one transaction; see below.
    private val transactions: Transactions = Injekt.get(),
) {
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    // RK: flags may be passed as a value so concurrent migrations can't read each other's set out of
    // the shared preference; skipTargetRefresh lets a caller that just fetched the target's chapters
    // say so, instead of paying the source for the identical request again. Both default to
    // upstream's behavior, so upstream's own call sites are unchanged.
    suspend operator fun invoke(
        current: Manga,
        target: Manga,
        replace: Boolean,
        flags: Set<MigrationFlag> = sourcePreferences.migrationFlags.get(),
        skipTargetRefresh: Boolean = false,
    ) {
        // RK: both guards are the engine's own, not the caller's. Upstream returned silently when the
        // target source was missing, and never checked for a self-target at all, so a caller that
        // forgot either check got a migration reporting success with nothing done. A self-target
        // would also put favorite=false and favorite=true for one row into the same swap.
        if (current.id == target.id) return
        val targetSource = checkNotNull(sourceManager.get(target.source)) {
            "Target source ${target.source} unavailable"
        }
        val currentSource = sourceManager.get(current.source)

        try {
            // RK: capture the source's merge group before the target is favorited, so it's the source
            // plus its existing siblings (not the target, which shares the title on a clean match).
            val mergeGroup = mangaMergeManager.computeRelatedIds(current.id)

            // RK: guarded, see skipTargetRefresh above.
            if (!skipTargetRefresh) {
                updateMangaFromRemote(target, fetchChapters = true).getOrThrow()
            }

            // Update chapters read, bookmark and dateFetch
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)
                val mangaChapters = getChaptersByMangaId.await(target.id)

                val maxChapterRead = prevMangaChapters
                    .filter { it.read }
                    .maxOfOrNull { it.chapterNumber }

                val updatedMangaChapters = mangaChapters.map { mangaChapter ->
                    var updatedChapter = mangaChapter
                    if (updatedChapter.isRecognizedNumber) {
                        val prevChapter = prevMangaChapters
                            .find { it.isRecognizedNumber && it.chapterNumber == updatedChapter.chapterNumber }

                        if (prevChapter != null) {
                            updatedChapter = updatedChapter.copy(
                                dateFetch = prevChapter.dateFetch,
                                bookmark = prevChapter.bookmark,
                            )
                        }

                        if (maxChapterRead != null && updatedChapter.chapterNumber <= maxChapterRead) {
                            updatedChapter = updatedChapter.copy(read = true)
                        }
                    }

                    updatedChapter
                }

                val chapterUpdates = updatedMangaChapters.map { it.toChapterUpdate() }
                // RK: straight to the repository (one transaction, throws on failure) where upstream
                // went through UpdateChapter, which logs and swallows. A half-carried read state is
                // what the Failed row and its retry exist to prevent. Runs before the downloads are
                // deleted, so a retry after this throws heals everything.
                chapterRepository.updateAll(chapterUpdates)
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setMangaCategories.await(target.id, categoryIds)
            }

            // Update track
            getTracks.await(current.id).mapNotNull { track ->
                val updatedTrack = track.copy(mangaId = target.id)

                val service = enhancedServices
                    .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                if (service != null) {
                    service.migrateTrack(updatedTrack, target, targetSource)
                } else {
                    updatedTrack
                }
            }
                .takeIf { it.isNotEmpty() }
                ?.let { insertTrack.awaitAll(it) }

            // Delete downloaded
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                downloadManager.deleteManga(current, currentSource)
            }

            // Update custom cover (recheck if custom cover exists)
            // RK: pass the injected cache rather than letting hasCustomCover resolve its own from
            // Injekt. Same singleton in production, but it was the one dependency this use case did
            // not take by constructor, so the cover carry could not be tested at all.
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover(coverCache)) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
                // RK: bump the timestamp so Coil reloads, matching the novel engine and every other
                // custom-cover write. Without it a target that already had a custom cover keeps
                // showing the old one, since the cache key does not change.
                updateManga.awaitUpdateCoverLastModified(target.id)
            }

            val currentMangaUpdate = MangaUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetMangaUpdate = MangaUpdate(
                id = target.id,
                favorite = true,
                chapterFlags = current.chapterFlags,
                viewerFlags = current.viewerFlags,
                dateAdded = if (replace) current.dateAdded else Clock.System.now().toEpochMilliseconds(),
                notes = if (MigrationFlag.NOTES in flags) current.notes else null,
            )

            // RK --> the favorite swap and the merge-group rewrite are ONE unit of work, with the
            // swap genuinely last inside it. They used to be two transactions with a suspension point
            // between them, so a batch the user cancelled could commit the swap and never reach the
            // rewrite: the source ended up out of the library but still in the group, feeding
            // chapters into it while invisible there and unreachable to unmerge.
            transactions.run {
                // The target takes the source's place in the group on a replace, or joins it on a
                // copy. Works for manual and same-title auto groups.
                if (replace) {
                    mangaMergeManager.replaceInGroup(current.id, target.id)
                } else if (mergeGroup.size > 1) {
                    mangaMergeManager.merge(mergeGroup.toList() + target.id)
                }
                // Checked, where upstream discards the result. The swap is the step that decides which
                // entry is in the library; letting it fail quietly reports the row as Migrated with
                // the source still favorited. Throwing here rolls the group rewrite back with it.
                check(updateManga.awaitAll(listOfNotNull(currentMangaUpdate, targetMangaUpdate))) {
                    "Migration favorite swap failed (${current.id} -> ${target.id})"
                }
            }
            // RK <--
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            // RK: rethrown after logging, where upstream swallowed. A caller that shows per-row
            // outcomes has to be able to tell a failure from a success; the ones that don't care
            // catch it themselves.
            logcat(LogPriority.ERROR, e) { "Manga migration failed" }
            throw e
        }
    }
}
