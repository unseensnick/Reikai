package exh.md.handlers

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.md.dto.PersonalRatingDto
import exh.md.dto.ReadingStatusDto
import exh.md.service.MangaDexAuthService
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.md.utils.asMdMap
import kotlinx.coroutines.async
import tachiyomi.core.common.util.lang.withIOContext

// Per-title tracking for the MDList tracker. The paged follows-listing methods (fetchFollows,
// fetchAllFollows) that feed the library follows sync arrive with Phase 4.
class FollowsHandler(
    private val service: MangaDexAuthService,
) {

    /**
     * Change the status of a manga
     */
    suspend fun updateFollowStatus(mangaId: String, followStatus: FollowStatus): Boolean {
        return withIOContext {
            val status = when (followStatus == FollowStatus.UNFOLLOWED) {
                true -> null
                false -> followStatus.toDex()
            }
            val readingStatusDto = ReadingStatusDto(status)

            if (followStatus == FollowStatus.UNFOLLOWED) {
                service.unfollowManga(mangaId)
            } else {
                service.followManga(mangaId)
            }

            service.updateReadingStatusForManga(mangaId, readingStatusDto).result == "ok"
        }
    }

    suspend fun updateRating(track: Track): Boolean {
        return withIOContext {
            val mangaId = MdUtil.getMangaId(track.tracking_url)
            val result = runCatching {
                if (track.score == 0.0) {
                    service.deleteMangaRating(mangaId)
                } else {
                    service.updateMangaRating(mangaId, track.score.toInt())
                }.result == "ok"
            }
            result.getOrDefault(false)
        }
    }

    suspend fun fetchTrackingInfo(url: String): Track {
        return withIOContext {
            val mangaId = MdUtil.getMangaId(url)
            val followStatusDef = async {
                FollowStatus.fromDex(service.readingStatusForManga(mangaId).status)
            }
            val ratingDef = async {
                service.mangasRating(mangaId).ratings.asMdMap<PersonalRatingDto>()[mangaId]
            }
            val (followStatus, rating) = followStatusDef.await() to ratingDef.await()
            Track.create(TrackerManager.MDLIST).apply {
                title = ""
                status = followStatus.long
                tracking_url = url
                score = rating?.rating?.toDouble() ?: 0.0
            }
        }
    }
}
