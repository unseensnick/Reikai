package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.track.source.SourceTrackerDispatcher
import kotlin.time.Clock

/**
 * Surgical single/few-column writes to the novels table, the novel twin of
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
            NovelUpdate(novelId) { coverLastModified = Clock.System.now().toEpochMilliseconds() },
        )
    }

    suspend fun awaitUpdateFavorite(novelId: Long, favorite: Boolean): Boolean {
        val update = when (favorite) {
            true -> NovelUpdate(novelId) { favoriteAt = Clock.System.now().toEpochMilliseconds() }
            false -> NovelUpdate(novelId) { favoriteAt = null }
        }
        return novelRepository.update(update)
            // An extension syncing to its own site hears of an add or remove made through here.
            .also { updated -> if (updated) sourceTracker.favoriteChanged(EntryId.Novel(novelId), favorite) }
    }
}
