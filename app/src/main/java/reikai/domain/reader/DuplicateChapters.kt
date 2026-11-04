package reikai.domain.reader

import reikai.domain.chapter.chapterCopySets

/**
 * Drop duplicate chapters WITHIN one entry, which a source produces by listing a chapter twice or under
 * several scanlators; what counts as a copy is [chapterCopySets]. Of each set the chapter being read
 * wins, then the copies with the lowest [rank], of those the same origin (scanlator) as the current
 * chapter first, then the first, kept where it is listed. Dropping them rather than stepping over them
 * keeps the chapter sheet, download-ahead and delete-after-read counting the chapters the reader stops
 * on. [ownerOf] keeps the pass inside one entry: across a merge group a number identifies nothing.
 */
fun <T> List<T>.removeDuplicateChapters(
    current: T,
    rank: (T) -> Int,
    numberOf: (T) -> Double,
    nameOf: (T) -> String,
    idOf: (T) -> Long,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
): List<T> {
    val currentId = idOf(current)
    val currentOrigin = originOf(current)
    val kept = chapterCopySets(numberOf, nameOf, originOf, ownerOf).mapTo(HashSet()) { copies ->
        val chosen = copies.find { idOf(it) == currentId } ?: run {
            val best = copies.minOf(rank)
            val ranked = copies.filter { rank(it) == best }
            ranked.find { originOf(it) == currentOrigin } ?: ranked.first()
        }
        idOf(chosen)
    }
    return filter { idOf(it) in kept }
}

/**
 * What finishing [read] also marks read under "mark duplicate read chapter as read", [read] excluded.
 * Within its own entry that is [read]'s copies by [chapterCopySets], which is how another scanlator's
 * copy is reached. Across a merge group a number identifies nothing, so there only [stitchCopies], the
 * chapters the stored stitch places with [read], count.
 */
fun <T> List<T>.duplicatesOfRead(
    read: T,
    stitchCopies: Set<Long>,
    numberOf: (T) -> Double,
    nameOf: (T) -> String,
    idOf: (T) -> Long,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
): List<T> {
    val readId = idOf(read)
    val readOwner = ownerOf(read)
    // Narrowed to Float: a source-reported number is a float, a parsed one a double, and both must match.
    val readNumber = numberOf(read).toFloat()
    val sameNumber = filter { ownerOf(it) == readOwner && numberOf(it).toFloat() == readNumber }
    val copies = (sameNumber + read).distinctBy(idOf)
        .chapterCopySets({ readNumber.toDouble() }, nameOf, originOf, ownerOf)
        .first { set -> set.any { idOf(it) == readId } }
        .mapTo(HashSet(), idOf)
    return filter { idOf(it) != readId && (idOf(it) in stitchCopies || idOf(it) in copies) }
}

/**
 * What a reader pages through while the global Downloaded only switch is on: the chapters on disk, and
 * the one being read even when it is not, so a chapter opened from History or Updates keeps its place.
 * Download-ahead must walk the list before this: under the switch this one holds nothing left to fetch.
 */
fun <T> List<T>.downloadedOrCurrent(current: T, idOf: (T) -> Long, downloadedIds: Set<Long>): List<T> =
    filter { idOf(it) == idOf(current) || idOf(it) in downloadedIds }

/**
 * The chapters a reader steps through: user-hidden ones dropped, the one being read kept, and then, with
 * skip-duplicate on, copies removed. Hidden first, so a copy nothing shows can never be the
 * one a duplicate group keeps and take the whole number with it. For the same reason a copy in
 * [downloadedOnlyIds], the chapters on disk while Downloaded only is on (null while it is off), is kept
 * first, then one a forward step may land on ([isForwardEligible]). Download-ahead calls this per page turn
 * so it queues what the reader stops on, hence the on-disk set (a probe per chapter) is asked for lazily.
 */
fun <T> List<T>.navigableChapters(
    current: T,
    isHidden: (T) -> Boolean,
    isForwardEligible: (T) -> Boolean,
    downloadedOnlyIds: () -> Set<Long>?,
    skipDuplicates: Boolean,
    numberOf: (T) -> Double,
    nameOf: (T) -> String,
    idOf: (T) -> Long,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
): List<T> {
    val shown = filter { idOf(it) == idOf(current) || !isHidden(it) }
    if (!skipDuplicates) return shown
    val downloadedOnlyIds = downloadedOnlyIds()
    val rank = { chapter: T ->
        val offDisk = downloadedOnlyIds != null && idOf(chapter) !in downloadedOnlyIds
        (if (offDisk) 2 else 0) + (if (isForwardEligible(chapter)) 0 else 1)
    }
    return shown.removeDuplicateChapters(current, rank, numberOf, nameOf, idOf, originOf, ownerOf)
}
