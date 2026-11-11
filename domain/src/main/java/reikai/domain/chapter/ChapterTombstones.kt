package reikai.domain.chapter

import reikai.domain.library.ContentType
import kotlin.time.Instant

/** A chapter as a sync names it to the tombstone rules: its stored [id] (unset for one arriving), url and number. */
data class SyncedChapter(val id: Long, val url: String, val number: Double)

/** An arriving chapter at [url] and the tombstone, at [tombstoneUrl], that comes back onto it. */
data class Revival(val url: String, val tombstoneUrl: String)

/** What a sync's write returned, with the [urls] of the chapters that arrived carrying a tombstone. */
data class Revived<T>(val result: T, val urls: Set<String>)

/** One reading session a tombstone keeps, as the reading log keeps it. */
data class TombstoneSession(val endedAt: Long, val duration: Long)

/**
 * A removed local chapter's whole reading state, whole for a backup: [progress] is the type's resume
 * position, [length] its page count or word count (null for a novel chapter never counted), [listenedMs]
 * a novel's read-aloud share, [historyDuration] null when the chapter had no history row and
 * [historyReadAt] null for history the user removed.
 */
data class ChapterTombstone(
    val url: String,
    val number: Double,
    val read: Boolean,
    val bookmark: Boolean,
    val progress: Long,
    val length: Long?,
    val listenedMs: Long?,
    val dateFetch: Instant,
    val historyReadAt: Instant?,
    val historyDuration: Long?,
    val sessions: List<TombstoneSession>,
    val removedAt: Long,
)

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
 * is confirmed gone once [listed] still reads that container, since the container itself was read; any
 * other is asked of [present], a trusted listing of its folder, never the file's own query.
 */
suspend fun unconfirmedRemovals(
    removed: List<String>,
    listed: Collection<String>,
    containerOf: (String) -> String?,
    present: suspend (String) -> Boolean,
): List<String> = removed.filter { url ->
    val container = containerOf(url)
    val containerRead = container != null && listed.any { it.startsWith(container) }
    !containerRead && present(url)
}

/**
 * The tombstone tables. Every write onto a key already held, and every tombstone applied onto a chapter
 * row, folds by one rule, pinned by ChapterTombstoneFoldConformanceTest: read and bookmark stick,
 * progress, length, listened time and history time take the larger, the fetch date the earlier, history
 * the later read, sessions the union.
 */
interface ChapterTombstoneRepository {
    /** The tombstones [ownerId] holds, by url and number. */
    suspend fun byOwner(type: ContentType, ownerId: Long): List<SyncedChapter>

    /** Every tombstone of each of [ownerIds], whole. */
    suspend fun getByOwners(type: ContentType, ownerIds: List<Long>): Map<Long, List<ChapterTombstone>>

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

    /**
     * Moves the tombstone at [fromUrl] of [fromOwner] under [toOwner] and [toUrl], folding onto one already
     * there. Called inside the transaction that moves the chapter's address; [settle] follows it.
     */
    suspend fun move(type: ContentType, fromOwner: Long, fromUrl: String, toOwner: Long, toUrl: String)

    /** Applies and consumes each of [ownerId]'s tombstones whose url a chapter row holds again. */
    suspend fun settle(type: ContentType, ownerId: Long)

    /** Drops [ownerId]'s tombstones at urls starting with any of [urlPrefixes]: the user deleted those files. */
    suspend fun forget(type: ContentType, ownerId: Long, urlPrefixes: List<String>)

    /** Folds a backup's [tombstones] in under [ownerId], then settles them against its chapter rows. */
    suspend fun restore(type: ContentType, ownerId: Long, tombstones: List<ChapterTombstone>)
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
