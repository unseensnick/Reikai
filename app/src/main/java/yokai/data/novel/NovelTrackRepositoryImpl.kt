package yokai.data.novel

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.novel.models.NovelTrack

class NovelTrackRepositoryImpl(private val handler: DatabaseHandler) : NovelTrackRepository {

    override suspend fun getByNovelId(novelId: Long): List<NovelTrack> =
        handler.awaitList { novel_tracksQueries.getAllByNovelId(novelId, ::novelTrackMapper) }

    override fun observeByNovelId(novelId: Long): Flow<List<NovelTrack>> =
        handler.subscribeToList { novel_tracksQueries.getAllByNovelId(novelId, ::novelTrackMapper) }

    /**
     * Insert wrapped in the upsert call: novel_tracks declares UNIQUE(novel_id, sync_id) ON
     * CONFLICT REPLACE, so the insert itself handles re-binding the same novel/tracker pair
     * without callers needing a separate update path.
     */
    override suspend fun upsert(track: NovelTrack): Long? = try {
        handler.awaitOneOrNullExecutable(inTransaction = true) {
            novel_tracksQueries.insert(
                novelId = track.novelId,
                syncId = track.syncId,
                remoteId = track.remoteId,
                libraryId = track.libraryId,
                title = track.title,
                lastChapterRead = track.lastChapterRead.toDouble(),
                totalChapters = track.totalChapters,
                status = track.status.toLong(),
                score = track.score.toDouble(),
                remoteUrl = track.remoteUrl,
                startDate = track.startDate,
                finishDate = track.finishDate,
            )
            novel_tracksQueries.selectLastInsertedRowId()
        }
    } catch (e: Exception) {
        Logger.e(e) { "Failed to upsert novel track for novelId=${track.novelId} syncId=${track.syncId}" }
        null
    }

    override suspend fun delete(novelId: Long, syncId: Long) {
        handler.await { novel_tracksQueries.deleteForNovel(novelId, syncId) }
    }

    override suspend fun deleteAllForNovel(novelId: Long) {
        handler.await { novel_tracksQueries.deleteAllForNovel(novelId) }
    }
}
