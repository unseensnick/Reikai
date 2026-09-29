package reikai.presentation.migrate.flow

import reikai.presentation.migrate.PickMember

/** Whether [url] on [sourceKey] is this entry's own listing, which is never a migration target. */
fun MigrationEntry.isOwnListing(sourceKey: String, url: String): Boolean {
    val ownUrl = when (payload) {
        is MigrationPayload.OfManga -> payload.manga.url
        is MigrationPayload.OfNovel -> payload.novel.url
    }
    return sourceKey == this.sourceKey && url == ownUrl
}

/** Every stored entry in [ids] expanded to its merge group ([relatedIds]), deduped in encounter order. */
suspend fun <T : Any> mergeGroupPickMembers(
    ids: List<Long>,
    load: suspend (Long) -> T?,
    relatedIds: suspend (Long) -> LongArray,
    toMember: suspend (T) -> PickMember,
): List<PickMember> {
    val memberIds = LinkedHashSet<Long>()
    ids.forEach { id -> if (load(id) != null) memberIds += relatedIds(id).asList() }
    return memberIds.mapNotNull { id -> load(id)?.let { toMember(it) } }
}

/** The flags worth offering: CHAPTER and CATEGORY always, the rest only when some entry has the thing. */
fun <T> applicableFlagsOf(
    entries: List<T>,
    hasCustomCover: (T) -> Boolean,
    hasNotes: (T) -> Boolean,
    hasDownloads: (T) -> Boolean,
): Set<MigrationDataFlag> = MigrationDataFlag.entries.filterTo(LinkedHashSet()) { flag ->
    when (flag) {
        MigrationDataFlag.CHAPTER -> true
        MigrationDataFlag.CATEGORY -> true
        MigrationDataFlag.CUSTOM_COVER -> entries.any(hasCustomCover)
        MigrationDataFlag.NOTES -> entries.any(hasNotes)
        MigrationDataFlag.REMOVE_DOWNLOAD -> entries.any(hasDownloads)
    }
}
