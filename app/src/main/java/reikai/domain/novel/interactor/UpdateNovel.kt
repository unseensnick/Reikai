package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.entry.EntryId
import reikai.domain.library.libraryMembershipChanged
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher
import kotlin.time.Clock

/**
 * Surgical single/few-column writes to the novels table, shaped after
 * [eu.kanade.domain.manga.interactor.UpdateManga]. Routes through the repo's coalesce-based partial
 * update so a write touches only the columns it sets, instead of a full-row read-modify-write.
 */
@Inject
class UpdateNovel(
    private val novelRepository: NovelRepository,
    private val sourceTracker: SourceTrackerDispatcher,
) {

    suspend fun await(update: NovelUpdate): Boolean {
        return novelRepository.update(update)
    }

    suspend fun awaitUpdateCoverLastModified(novelId: Long): Boolean {
        return novelRepository.update(
            NovelUpdate(novelId) { coverLastModified = Clock.System.now() },
        )
    }

    // Twin of UpdateManga.awaitUpdateFavorite, pinned by EntryUpdateTwinsConformanceTest.
    suspend fun awaitUpdateFavorite(novelId: Long, favorite: Boolean): Boolean {
        val update = when (favorite) {
            true -> NovelUpdate(novelId) { favoriteAt = Clock.System.now() }
            false -> NovelUpdate(novelId) { favoriteAt = null }
        }
        return novelRepository.update(update)
            .also { updated -> if (updated) sourceTracker.libraryMembershipChanged(EntryId.Novel(novelId), favorite) }
    }
}
