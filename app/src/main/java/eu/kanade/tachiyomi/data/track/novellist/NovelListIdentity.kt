package eu.kanade.tachiyomi.data.track.novellist

import java.util.UUID
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * NovelList identifies a novel by UUID, but `remote_id` is a `Long`, so the UUID rides in the
 * tracking URL's fragment and `remote_id` holds a derived surrogate.
 *
 * The fragment rather than the path because the site answers 404 for `/novels/{uuid}` and only
 * resolves a slug, so a UUID path would break the link the user opens. Browsers ignore a fragment,
 * so one URL serves both. The surrogate exists because a shared `remote_id` of 0 would make the
 * merge-group propagation guard treat unrelated novels as the same entry.
 */

private const val UUID_SEPARATOR = "#"

internal fun novelListTrackingUrl(slug: String, uuid: String): String =
    "${NovelListApi.novelUrl(slug)}$UUID_SEPARATOR$uuid"

internal val DbTrack.uuid: String
    get() = tracking_url.substringAfter(UUID_SEPARATOR, "")

internal val DomainTrack.uuid: String
    get() = remoteUrl.substringAfter(UUID_SEPARATOR, "")

internal fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

/**
 * Sixty-four bits of the UUID, kept positive. Not an identity: nothing selects on `remote_id`, and
 * the unique key is `(novel_id, sync_id)`. The reference fork folds a 32-bit `String.hashCode()`
 * into the same column and then treats it as one.
 */
internal fun surrogateIdOf(uuid: String): Long =
    runCatching { UUID.fromString(uuid).mostSignificantBits and Long.MAX_VALUE }
        .getOrDefault(uuid.hashCode().toLong() and Long.MAX_VALUE)
