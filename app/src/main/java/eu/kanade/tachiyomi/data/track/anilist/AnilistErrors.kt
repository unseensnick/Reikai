package eu.kanade.tachiyomi.data.track.anilist

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation

/**
 * Surfaces AniList's GraphQL errors (downtime, an expired token) as a readable failure, as the raw
 * client did (from Komikku ca26501aef). Upstream's dataOrElse only logs them and falls back to its
 * default, which turns an expired login into an empty search.
 */
internal fun <D : Operation.Data> ApolloResponse<D>.throwOnAniListError(): ApolloResponse<D> {
    val error = errors?.firstOrNull() ?: return this
    val status = (error.nonStandardFields?.get("status") as? Number)?.toInt()
    if ("Invalid token" in error.message || status == 401) {
        throw Exception("AniList token expired, please login again")
    }
    throw Exception(error.message)
}
