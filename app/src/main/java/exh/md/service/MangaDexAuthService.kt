package exh.md.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import exh.md.dto.RatingDto
import exh.md.dto.RatingResponseDto
import exh.md.dto.ReadingStatusDto
import exh.md.dto.ResultDto
import exh.md.utils.MdApi
import exh.md.utils.MdUtil
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// Authenticated MangaDex API used by the MDList tracker: per-title follow status and rating. The
// follows-listing calls (userFollowList, readingStatusAllManga, chapter read-markers) arrive with
// Phase 4 (library follows sync). Every call carries the OAuth bearer via the interceptor on the
// client plus the extension headers, which the MangaDex API requires (a browser UA gets a 400).
class MangaDexAuthService(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    suspend fun readingStatusForManga(mangaId: String): ReadingStatusDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                GET(
                    "${MdApi.manga}/$mangaId/status",
                    headers,
                    CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun updateReadingStatusForManga(
        mangaId: String,
        readingStatusDto: ReadingStatusDto,
    ): ResultDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                POST(
                    "${MdApi.manga}/$mangaId/status",
                    headers,
                    body = MdUtil.encodeToBody(readingStatusDto),
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun followManga(mangaId: String): ResultDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                POST(
                    "${MdApi.manga}/$mangaId/follow",
                    headers,
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun unfollowManga(mangaId: String): ResultDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                Request.Builder()
                    .url("${MdApi.manga}/$mangaId/follow")
                    .delete()
                    .headers(headers)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build(),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun updateMangaRating(mangaId: String, rating: Int): ResultDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                POST(
                    "${MdApi.rating}/$mangaId",
                    headers,
                    body = MdUtil.encodeToBody(RatingDto(rating)),
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun deleteMangaRating(mangaId: String): ResultDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                Request.Builder()
                    .delete()
                    .url("${MdApi.rating}/$mangaId")
                    .headers(headers)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build(),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun mangasRating(vararg mangaIds: String): RatingResponseDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                GET(
                    MdApi.rating.toHttpUrl()
                        .newBuilder()
                        .apply {
                            mangaIds.forEach {
                                addQueryParameter("manga[]", it)
                            }
                        }
                        .build(),
                    headers,
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }
}
