package reikai.domain.novel.interactor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import logcat.LogPriority
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.util.system.logcat

class GetNovelTracks(
    private val repository: NovelTrackRepository,
    private val mergeManager: NovelMergeManager,
) {

    suspend fun awaitOne(id: Long): NovelTrack? {
        return try {
            repository.getTrackById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    suspend fun await(novelId: Long): List<NovelTrack> {
        return try {
            repository.getTracksByNovelId(novelId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    fun subscribe(novelId: Long): Flow<List<NovelTrack>> = repository.getTracksByNovelIdAsFlow(novelId)

    /** Every novel track in the library, grouped by novel id. Mirrors [GetTracksPerManga.subscribe]. */
    fun subscribeAll(): Flow<Map<Long, List<NovelTrack>>> =
        repository.getTracksAsFlow().map { tracks -> tracks.groupBy(NovelTrack::novelId) }

    /**
     * Tracks bound on any member of [novelId]'s merge group, one per tracker. A track bound on one
     * source of a merged novel must be visible when reading/displaying any other source, so tracking
     * spans the whole group; deduped by tracker so the same tracker copied onto several members (e.g.
     * by [reikai.domain.novel.track.PropagateNovelTrackerLinks]) still counts/shows once.
     */
    suspend fun awaitGroup(novelId: Long): List<NovelTrack> =
        mergeManager.relatedNovelIdsFor(novelId).flatMap { await(it) }.distinctBy { it.trackerId }

    /**
     * Reactive [awaitGroup]. Emits this novel's own tracks immediately, so the details tracking icon
     * shows attached trackers as fast as the manga side (whose count is single-id), then refines to the
     * full merge group once it resolves (resolving the group loads the favorites list, which we don't
     * want to block the first emission on).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribeGroup(novelId: Long): Flow<List<NovelTrack>> =
        flow { emit(mergeManager.relatedNovelIdsFor(novelId)) }
            .onStart { emit(listOf(novelId)) }
            .distinctUntilChanged()
            .flatMapLatest { groupIds ->
                combine(
                    groupIds.map {
                        subscribe(it)
                    },
                ) { it.toList().flatten().distinctBy { track -> track.trackerId } }
            }
}
