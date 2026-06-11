package eu.kanade.tachiyomi.data.track.shikimori.dto

import kotlinx.serialization.Serializable

/**
 * RK: wire types for the Shikimori GraphQL `userRates` query used by the recommendation taste
 * profile. GraphQL is used (not the v2 REST `user_rates`) because only it returns genres inline.
 * Manga `id` is a GraphQL `ID` (string); the fetcher parses it to Long.
 */
@Serializable
data class SMUserRatesResponse(
    val data: SMUserRatesData = SMUserRatesData(),
)

@Serializable
data class SMUserRatesData(
    val userRates: List<SMUserRate> = emptyList(),
)

@Serializable
data class SMUserRate(
    val score: Int = 0,
    val status: String? = null,
    val manga: SMUserRateManga? = null,
)

@Serializable
data class SMUserRateManga(
    val id: String,
    val name: String,
    val genres: List<SMUserRateGenre> = emptyList(),
)

@Serializable
data class SMUserRateGenre(
    val name: String,
)
