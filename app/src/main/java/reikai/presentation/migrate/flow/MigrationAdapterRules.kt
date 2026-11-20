package reikai.presentation.migrate.flow

import reikai.presentation.migrate.PickMember

/** Whether [url] on [sourceKey] is this entry's own listing, which is never a migration target. */
fun MigrationEntry.isOwnListing(sourceKey: String, url: String): Boolean =
    sourceKey == this.sourceKey && url == ownUrl

/** The url of this entry's own listing on [MigrationEntry.sourceKey]. */
val MigrationEntry.ownUrl: String
    get() = when (payload) {
        is MigrationPayload.OfManga -> payload.manga.url
        is MigrationPayload.OfNovel -> payload.novel.url
    }

/**
 * Every entry in [ids] expanded to its merge group ([relatedIds]), deduped in encounter order, with an
 * entry no longer stored dropped by [load]. Null when the groups add nothing to [ids]: there is no choice
 * to offer, so no row is loaded for it.
 */
suspend fun <T : Any> mergeGroupPickMembers(
    ids: List<Long>,
    relatedIds: Map<Long, List<Long>>,
    load: suspend (Long) -> T?,
    toMember: suspend (T) -> PickMember,
): List<PickMember>? {
    val memberIds = ids.flatMapTo(LinkedHashSet()) { relatedIds[it] ?: listOf(it) }
    if (memberIds == ids.toSet()) return null
    return memberIds.mapNotNull { id -> load(id)?.let { toMember(it) } }
}

/** The flags worth offering: CHAPTER and CATEGORY always, the rest only when some entry has the thing. */
fun <T> applicableFlagsOf(
    entries: List<T>,
    hasCustomCover: (T) -> Boolean,
    hasNotes: (T) -> Boolean,
    hasDownloads: (T) -> Boolean,
    hasCustomInfo: (T) -> Boolean,
): Set<MigrationDataFlag> = MigrationDataFlag.entries.filterTo(LinkedHashSet()) { flag ->
    when (flag) {
        MigrationDataFlag.CHAPTER -> true
        MigrationDataFlag.CATEGORY -> true
        MigrationDataFlag.CUSTOM_COVER -> entries.any(hasCustomCover)
        MigrationDataFlag.NOTES -> entries.any(hasNotes)
        MigrationDataFlag.REMOVE_DOWNLOAD -> entries.any(hasDownloads)
        MigrationDataFlag.CUSTOM_INFO -> entries.any(hasCustomInfo)
    }
}
