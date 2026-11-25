package reikai.domain.download

/**
 * Ids of [chapters] on disk, each asked of its own owner ([ownerOf], keyed by [ownerKey]) rather than the
 * screen's entry: a merged series' copy is stored under the source it came from. A local owner's chapters
 * are its own files, so they all are; a chapter whose owner is missing is not. Each owner is probed once,
 * for all its chapters. Both content types' downloadedChapterIds answer through here.
 */
fun <C, O : Any> ownedOnDisk(
    chapters: List<C>,
    ownerKey: (C) -> Long,
    ownerOf: (C) -> O?,
    isLocal: (O) -> Boolean,
    id: (C) -> Long,
    probe: (owner: O, owned: List<C>) -> Collection<Long>,
): Set<Long> = chapters.groupBy(ownerKey).values.flatMapTo(HashSet()) { owned ->
    val owner = ownerOf(owned.first()) ?: return@flatMapTo emptyList()
    if (isLocal(owner)) owned.map(id) else probe(owner, owned)
}

/** One copy's answer by [ownedOnDisk]'s rule, for a caller holding only its stored values. */
inline fun isOwnedOnDevice(ownerIsLocal: Boolean, probe: () -> Boolean): Boolean = ownerIsLocal || probe()
