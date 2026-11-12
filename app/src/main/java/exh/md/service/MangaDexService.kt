package exh.md.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import exh.md.dto.AggregateDto
import exh.md.dto.ChapterDto
import exh.md.dto.MangaDto
import exh.md.dto.MangaListDto
import exh.md.dto.StatisticsDto
import exh.md.utils.MdApi
import exh.md.utils.MdConstants
import exh.md.utils.MdLang
import exh.md.utils.MdUtil
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

// The MangaDex API rejects browser User-Agents (400 with an HTML body). Reikai's network client
// injects a browser UA by default (for Cloudflare bypass), so every request carries the delegate's
// headers, which set the extension's "Tachiyomi ..." UA, the same way the stock extension does.
class MangaDexService(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    suspend fun viewMangas(
        ids: List<String>,
    ): MangaListDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                GET(
                    MdApi.manga.toHttpUrl()
                        .newBuilder()
                        .apply {
                            addQueryParameter("includes[]", MdConstants.Types.coverArt)
                            addQueryParameter("includes[]", MdConstants.Types.author)
                            addQueryParameter("includes[]", MdConstants.Types.artist)
                            addQueryParameter("limit", ids.size.toString())
                            ids.forEach {
                                addQueryParameter("ids[]", it)
                            }
                        }
                        .build(),
                    headers = headers,
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun viewManga(
        id: String,
    ): MangaDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                GET(
                    MdApi.manga.toHttpUrl()
                        .newBuilder()
                        .apply {
                            addPathSegment(id)
                            addQueryParameter("includes[]", MdConstants.Types.coverArt)
                            addQueryParameter("includes[]", MdConstants.Types.author)
                            addQueryParameter("includes[]", MdConstants.Types.artist)
                        }
                        .build(),
                    headers = headers,
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun mangasRating(
        vararg ids: String,
    ): StatisticsDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                GET(
                    MdApi.statistics.toHttpUrl()
                        .newBuilder()
                        .apply {
                            ids.forEach { id ->
                                addQueryParameter("manga[]", id)
                            }
                        }
                        .build(),
                    headers = headers,
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun aggregateChapters(
        id: String,
        translatedLanguage: String,
    ): AggregateDto {
        return with(MdUtil.jsonParser) {
            client.newCall(
                GET(
                    aggregateUrl(id, translatedLanguage),
                    headers = headers,
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess().parseAs()
        }
    }

    suspend fun viewChapter(id: String): ChapterDto {
        return with(MdUtil.jsonParser) {
            client.newCall(GET("${MdApi.chapter}/$id", headers = headers, cache = CacheControl.FORCE_NETWORK))
                .awaitSuccess()
                .parseAs()
        }
    }

    suspend fun randomManga(): MangaDto {
        return with(MdUtil.jsonParser) {
            client.newCall(GET("${MdApi.manga}/random", headers = headers, cache = CacheControl.FORCE_NETWORK))
                .awaitSuccess()
                .parseAs()
        }
    }

    companion object {
        internal fun aggregateUrl(id: String, translatedLanguage: String): HttpUrl =
            MdApi.manga.toHttpUrl()
                .newBuilder()
                .addPathSegment(id)
                .addPathSegment("aggregate")
                .apply {
                    if (translatedLanguage != MdLang.ALL.lang) {
                        addQueryParameter("translatedLanguage[]", translatedLanguage)
                    }
                }
                .build()
    }
}
