package exh.md.handlers

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.model.SManga
import exh.md.dto.MangaDataDto
import exh.md.dto.PersonalRatingDto
import exh.md.dto.ReadingStatusDto
import exh.md.service.MangaDexAuthService
import exh.md.utils.FollowStatus
import exh.md.utils.MdConstants
import exh.md.utils.MdUtil
import exh.md.utils.asMdMap
import exh.md.utils.mdListCall
import exh.metadata.metadata.MangaDexSearchMetadata
import kotlinx.coroutines.async
import tachiyomi.core.common.util.lang.withIOContext

// Per-title tracking for the MDList tracker plus the library follows-listing (fetchFollows /
// fetchAllFollows) that feed the follows sync. `lang` picks which title translation to surface.
class FollowsHandler(
    private val lang: String,
    private val service: MangaDexAuthService,
) {

    /**
     * Fetch one page of the signed-in user's follows.
     */
    suspend fun fetchFollows(page: Int): MetadataMangasPage {
        return withIOContext {
            // page is 1-based (BaseSourcePagingSource), so page 1 starts at offset 0. Offset step and
            // request limit are the same constant, which fixes Komikku's overlap/skip (it stepped 20).
            val follows = service.userFollowList(MdConstants.followsPageLimit * (page - 1))

            if (follows.data.isEmpty()) {
                return@withIOContext MetadataMangasPage(emptyList(), false, emptyList())
            }

            val hasMoreResults = follows.limit + follows.offset < follows.total
            val statusListResponse = service.readingStatusAllManga()
            val results = followsParseMangaPage(follows.data, statusListResponse.statuses)

            MetadataMangasPage(results.map { it.first }, hasMoreResults, results.map { it.second })
        }
    }

    /**
     * Fetch every follow across all pages (for the bulk "Sync Follows to Library" action).
     */
    suspend fun fetchAllFollows(): List<Pair<SManga, MangaDexSearchMetadata>> {
        return withIOContext {
            val results = async { mdListCall { service.userFollowList(it) } }
            val readingStatusResponse = async { service.readingStatusAllManga().statuses }
            followsParseMangaPage(results.await(), readingStatusResponse.await())
        }
    }

    /**
     * Map a follows API response into manga + follow-status metadata, sorted by status then title.
     */
    private fun followsParseMangaPage(
        response: List<MangaDataDto>,
        statuses: Map<String, String?>,
    ): List<Pair<SManga, MangaDexSearchMetadata>> {
        val comparator = compareBy<Pair<SManga, MangaDexSearchMetadata>> { it.second.followStatus }
            .thenBy { it.first.title }

        return response.map {
            MdUtil.createMangaEntry(it, lang) to MangaDexSearchMetadata().apply {
                followStatus = FollowStatus.fromDex(statuses[it.id]).long.toInt()
            }
        }.sortedWith(comparator)
    }

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
