package reikai.domain.chapter

import reikai.domain.library.ContentType

/** A chapter as a sync names it to the tombstone rules: its stored [id] (unset for one arriving), url and number. */
data class SyncedChapter(val id: Long, val url: String, val number: Double)

/** An arriving chapter at [url] and the tombstone, at [tombstoneUrl], that comes back onto it. */
data class Revival(val url: String, val tombstoneUrl: String)

/** What a sync's write returned, with the [urls] of the chapters that arrived carrying a tombstone. */
data class Revived<T>(val result: T, val urls: Set<String>)

/** A local refresh listed no file for these chapters, yet the files are there: the listing was short. */
class LocalChapterStillPresentException(val urls: List<String>) :
    Exception("A local refresh dropped chapters whose files still exist: ${urls.joinToString()}")

/**
 * Which tombstone each [arriving] chapter takes, from the owner's [tombstoned] and this run's [removed]
 * chapters, which become tombstones in the same write: its exact url, else the one url equal ignoring
 * case, else the one [removed] chapter it alone shares a recognized number with. Each tombstone revives
 * once, onto one arrival; a pair that is not unique on both sides takes nothing here, and the number
 * twins in [chapterArrivals] still give it read, bookmark and fetch date.
 */
fun tombstoneRevivals(
    arriving: List<SyncedChapter>,
    tombstoned: List<SyncedChapter>,
    removed: List<SyncedChapter>,
): List<Revival> {
    val pool = (tombstoned + removed).associateByTo(LinkedHashMap()) { it.url }
    val revivals = mutableListOf<Revival>()
    val unmatched = arriving.filter { chapter ->
        pool.remove(chapter.url)?.also { revivals += Revival(chapter.url, it.url) } == null
    }
    val poolByFold = pool.values.groupBy { it.url.lowercase() }
    val arrivingByFold = unmatched.groupBy { it.url.lowercase() }
    val unmatchedByCase = unmatched.filter { chapter ->
        val fold = chapter.url.lowercase()
        val twin = poolByFold[fold]?.singleOrNull()?.takeIf { arrivingByFold.getValue(fold).size == 1 }
        twin?.also {
            pool.remove(it.url)
            revivals += Revival(chapter.url, it.url)
        } == null
    }
    val removedByNumber = removed.filter {
        it.url in pool && isRecognizedChapterNumber(it.number)
    }.groupBy { it.number }
    val arrivingByNumber = unmatchedByCase.groupBy { it.number }
    unmatchedByCase.forEach { chapter ->
        val twin = removedByNumber[chapter.number]?.singleOrNull() ?: return@forEach
        if (arrivingByNumber.getValue(chapter.number).size == 1) revivals += Revival(chapter.url, twin.url)
    }
    return revivals
}

/**
 * The [removed] chapters a local sync may not take, because their file is still there: the listing was
 * short. A chapter kept inside a container ([containerOf]: a volume folder's Extras, a book's chapter)
 * is confirmed gone once [listed] still reads that container, since the container itself was read.
 */
suspend fun unconfirmedRemovals(
    removed: List<String>,
    listed: Collection<String>,
    containerOf: (String) -> String?,
    exists: suspend (String) -> Boolean,
): List<String> = removed.filter { url ->
    val container = containerOf(url)
    val containerRead = container != null && listed.any { it.startsWith(container) }
    !containerRead && exists(url)
}

interface ChapterTombstoneRepository {
    /** The tombstones [ownerId] holds, by url and number. */
    suspend fun byOwner(type: ContentType, ownerId: Long): List<SyncedChapter>

    /**
     * Runs [write], a sync's one transaction, inside one that first keeps the state of the rows at
     * [removedIds], which [write] deletes, and then revives each tombstone in [revivals] onto the row
     * [write] stored at its url, consuming it. A failure anywhere rolls all three back.
     */
    suspend fun <T> capturing(
        type: ContentType,
        ownerId: Long,
        removedIds: List<Long>,
        revivals: List<Revival>,
        write: suspend () -> T,
    ): T
}

/** The tombstone rules as both syncs call them, which only a local series ([isLocal]) runs. */
interface LocalChapterTombstones {
    /** Fails the sync with [LocalChapterStillPresentException] before a [removed] chapter whose file is still there. */
    suspend fun confirm(type: ContentType, isLocal: Boolean, removed: List<SyncedChapter>, listed: Collection<String>)

    /** [ChapterTombstoneRepository.capturing] around [write], with the revivals [tombstoneRevivals] finds. */
    suspend fun <T> around(
        type: ContentType,
        ownerId: Long,
        isLocal: Boolean,
        removed: List<SyncedChapter>,
        arriving: List<SyncedChapter>,
        write: suspend () -> T,
    ): Revived<T>
}
