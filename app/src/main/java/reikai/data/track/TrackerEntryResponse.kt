package reikai.data.track

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import eu.kanade.tachiyomi.network.dataOrElse

/**
 * The one entry a GraphQL tracker answered with. [pick] is null when it has no entry at the id, which
 * these trackers answer with 200 and an empty result rather than a 404, so that reads as missing; a
 * GraphQL error is a failure. Never an empty fill, which would fill nothing and say nothing.
 */
fun <D : Operation.Data, R : Any> ApolloResponse<D>.trackerEntryOrThrow(trackerName: String, pick: (D) -> R?): R =
    dataOrElse(
        errorLog = "$trackerName: Failed to get the entry",
        default = { throw Exception("Could not get the entry from $trackerName") },
    ) { pick(it) ?: throw TrackerEntryMissingException(trackerName) }
