package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class NovelTrackRepositoryImpl(
    private val database: Database,
) : NovelTrackRepository {

    override suspend fun getTrackById(id: Long): NovelTrack? =
        database.novel_tracksQueries.getTrackById(id, ::mapNovelTrack).awaitAsOneOrNull()

    override suspend fun getTracksByNovelId(novelId: Long): List<NovelTrack> =
        database.novel_tracksQueries.getTracksByNovelId(novelId, ::mapNovelTrack).awaitAsList()

    override fun getTracksByNovelIdAsFlow(novelId: Long): Flow<List<NovelTrack>> =
        database.novel_tracksQueries.getTracksByNovelId(novelId, ::mapNovelTrack).subscribeToList()

    override fun getTracksAsFlow(): Flow<List<NovelTrack>> =
        database.novel_tracksQueries.getTracks(::mapNovelTrack).subscribeToList()

    override suspend fun delete(novelId: Long, trackerId: Long) {
        try {
            database.novel_tracksQueries.delete(novelId = novelId, syncId = trackerId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to delete novel track novelId=$novelId syncId=$trackerId" }
        }
    }

    override suspend fun insert(track: NovelTrack) {
        try {
            database.novel_tracksQueries.insert(
                novelId = track.novelId,
                syncId = track.trackerId,
                remoteId = track.remoteId,
                libraryId = track.libraryId,
                title = track.title,
                lastChapterRead = track.lastChapterRead,
                totalChapters = track.totalChapters,
                status = track.status,
                score = track.score,
                remoteUrl = track.remoteUrl,
                startDate = track.startDate,
                finishDate = track.finishDate,
                private = track.private,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to insert novel track novelId=${track.novelId}" }
        }
    }
}

private fun mapNovelTrack(
    id: Long,
    novelId: Long,
    syncId: Long,
    remoteId: Long,
    libraryId: Long?,
    title: String,
    lastChapterRead: Double,
    totalChapters: Long,
    status: Long,
    score: Double,
    remoteUrl: String,
    startDate: Long,
    finishDate: Long,
    private: Boolean,
): NovelTrack = NovelTrack(
    id = id,
    novelId = novelId,
    trackerId = syncId,
    remoteId = remoteId,
    libraryId = libraryId,
    title = title,
    lastChapterRead = lastChapterRead,
    totalChapters = totalChapters,
    status = status,
    score = score,
    remoteUrl = remoteUrl,
    startDate = startDate,
    finishDate = finishDate,
    private = private,
)
