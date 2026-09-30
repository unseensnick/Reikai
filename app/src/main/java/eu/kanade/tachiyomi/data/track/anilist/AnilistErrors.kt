package eu.kanade.tachiyomi.data.track.anilist

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloHttpException
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import reikai.data.track.TrackerSignedOutException
import tachiyomi.core.common.util.system.logcat

/**
 * Surfaces AniList's GraphQL errors (downtime, an expired token) as a readable failure, as the raw
 * client did (from Komikku ca26501aef). Upstream's dataOrElse only logs them and falls back to its
 * default, which turns an expired login into an empty search.
 */
internal fun <D : Operation.Data> ApolloResponse<D>.throwOnAniListError(): ApolloResponse<D> {
    (exception as? ApolloHttpException)?.throwAsTrackerError()
    val error = errors?.firstOrNull() ?: return this
    val status = (error.nonStandardFields?.get("status") as? Number)?.toInt()
    if (isSignedOut(error.message, status)) throw TrackerSignedOutException("AniList")
    throw Exception(error.message)
}

// AniList sends its errors with a non-2xx status and plain application/json, which Apollo leaves
// undecoded in the exception. Reading the body consumes it, so dataOrElse could not: always throw.
private fun ApolloHttpException.throwAsTrackerError(): Nothing {
    val text = body?.use { it.readUtf8() }
    logcat(LogPriority.ERROR, this) { "AniList HTTP $statusCode: $text" }
    val error = text?.let { runCatching { firstError(it) }.getOrNull() }
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull
    val status = error?.get("status")?.jsonPrimitive?.intOrNull
    if (statusCode == 401 || isSignedOut(message, status)) throw TrackerSignedOutException("AniList")
    throw HttpException(statusCode).also { it.stackTrace = stackTrace }
}

private fun firstError(body: String): JsonObject? =
    Json.parseToJsonElement(body).jsonObject["errors"]?.jsonArray?.firstOrNull()?.jsonObject

private fun isSignedOut(message: String?, status: Int?): Boolean =
    status == 401 || message?.contains("Invalid token") == true
