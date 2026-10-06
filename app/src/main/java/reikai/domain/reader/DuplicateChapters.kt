package reikai.domain.reader

import reikai.domain.chapter.isRecognizedChapterNumber

/**
 * Drop same-numbered duplicate chapters WITHIN one entry, which a source produces by listing a chapter
 * twice or under several scanlators. Of each set the chapter being read wins, then a [prefer]red one
 * (a forward step may land on it), the same origin (scanlator) as the current chapter first, then
 * the first. Dropping them rather than stepping over them keeps the chapter sheet,
 * download-ahead and delete-after-read counting the chapters the reader will stop on. [ownerOf] keeps
 * the pass inside one entry: across a merge group a number identifies nothing.
 */
fun <T> List<T>.removeDuplicateChapters(
    current: T,
    prefer: (T) -> Boolean,
    numberOf: (T) -> Double,
    idOf: (T) -> Long,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
): List<T> {
    val currentId = idOf(current)
    val currentOrigin = originOf(current)
    // A number below zero is no number: a prologue and an afterword both read -1 and are two chapters.
    return groupBy { chapter ->
        val number = numberOf(chapter)
        Triple(ownerOf(chapter), number, if (isRecognizedChapterNumber(number)) null else idOf(chapter))
    }.map { (_, chapters) ->
        val preferred = chapters.filter(prefer)
        chapters.find { idOf(it) == currentId }
            ?: preferred.find { originOf(it) == currentOrigin }
            ?: preferred.firstOrNull()
            ?: chapters.find { originOf(it) == currentOrigin }
            ?: chapters.first()
    }
}

/**
 * What finishing [read] also marks read under "mark duplicate read chapter as read", [read] excluded.
 * Within its own entry that is every chapter with the same recognised number, upstream's rule, which is
 * how another scanlator's copy is reached. Across a merge group a number identifies nothing, so there
 * only [stitchCopies], the chapters the stored stitch places with [read], count.
 */
fun <T> List<T>.duplicatesOfRead(
    read: T,
    stitchCopies: Set<Long>,
    numberOf: (T) -> Double,
    idOf: (T) -> Long,
    ownerOf: (T) -> Long,
): List<T> {
    val readId = idOf(read)
    val readOwner = ownerOf(read)
    // Narrowed to Float as upstream compares: a source-reported number is a float, a parsed one a double.
    val readNumber = numberOf(read).toFloat()
    val readIsNumbered = isRecognizedChapterNumber(numberOf(read))
    return filter {
        val sameNumberInEntry = readIsNumbered && ownerOf(it) == readOwner && numberOf(it).toFloat() == readNumber
        idOf(it) != readId && (idOf(it) in stitchCopies || sameNumberInEntry)
    }
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
 * skip-duplicate on, same-numbered copies removed. Hidden first, and a copy the skip filters let a
 * forward step land on ([isForwardEligible]) preferred, so a copy the step passes over can never be the
 * one a duplicate group keeps and take the whole number with it. Both readers sort before calling this,
 * and download-ahead calls it too, so it queues what the reader stops on.
 */
fun <T> List<T>.navigableChapters(
    current: T,
    isHidden: (T) -> Boolean,
    isForwardEligible: (T) -> Boolean,
    skipDuplicates: Boolean,
    numberOf: (T) -> Double,
    idOf: (T) -> Long,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
): List<T> {
    val shown = filter { idOf(it) == idOf(current) || !isHidden(it) }
    if (!skipDuplicates) return shown
    return shown.removeDuplicateChapters(current, isForwardEligible, numberOf, idOf, originOf, ownerOf)
}
