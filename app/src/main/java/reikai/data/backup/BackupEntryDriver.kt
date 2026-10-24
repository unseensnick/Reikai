package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import reikai.domain.db.Transactions

/**
 * One content type's half of writing its series into a backup. The driver owns which series are backed
 * up, which optional parts each carries and how many are read at once; the type owns what a part holds,
 * since manga and novels are separate messages in a frozen wire format and cannot share fields. Each part
 * answers a whole chunk and writes onto each [B] itself.
 */
interface BackupEntryParts<E, B> {
    suspend fun favorites(): List<E>

    /** Series outside the library that still carry read progress, which "All read entries" keeps. */
    suspend fun readNotInLibrary(): List<E>

    /** Series outside the library that are still members of a merge group, whatever their progress. */
    suspend fun groupMembersOutsideLibrary(): List<E>

    /** Each series' own fields, in [entries]' order, plus any part the type writes whatever the options say. */
    suspend fun base(entries: List<E>): List<B>

    suspend fun chapters(batch: List<Pair<E, B>>)

    suspend fun categories(batch: List<Pair<E, B>>)

    suspend fun tracking(batch: List<Pair<E, B>>)

    suspend fun history(batch: List<Pair<E, B>>)

    suspend fun customInfo(batch: List<Pair<E, B>>)
}

/**
 * How many series a chunk reads with each query, as Mihon's manga creator takes them. Only one chunk's
 * chapters are held at once, and its ids stay far inside SQLite's limit on bound values.
 */
const val BACKUP_CHUNK = 100

/**
 * Every series these options back up. The series lists are read whole, but the backups, with their
 * chapters and history, are built [BACKUP_CHUNK] series at a time and emitted in order. Merge group
 * members outside the library always come along: their groups are written whatever the options say,
 * and a restore resolves a member only against a row it restored.
 */
fun <E, B> BackupOptions.backupEntries(parts: BackupEntryParts<E, B>): Flow<B> = flow {
    val readOnly = if (readEntries) parts.readNotInLibrary() else emptyList()
    val outsideLibrary = (readOnly + parts.groupMembersOutsideLibrary()).distinct()
    for (chunk in (parts.favorites() + outsideLibrary).chunked(BACKUP_CHUNK)) {
        backupChunk(chunk, parts).forEach { emit(it) }
        yield()
    }
}

suspend fun <E, B> BackupOptions.backupChunk(entries: List<E>, parts: BackupEntryParts<E, B>): List<B> {
    val batch = entries.zip(parts.base(entries))
    if (chapters) parts.chapters(batch)
    if (categories) parts.categories(batch)
    if (tracking) parts.tracking(batch)
    if (history) parts.history(batch)
    if (customInfo) parts.customInfo(batch)
    return batch.map { it.second }
}

/**
 * Hands each series in a chunk its rows from one chunk-wide read keyed by [id]. A series with no rows is
 * not written, so its field keeps the message's empty default.
 */
inline fun <E, B, T> List<Pair<E, B>>.writeRows(
    rows: Map<Long, List<T>>,
    id: (E) -> Long,
    write: (entry: E, backup: B, rows: List<T>) -> Unit,
) = forEach { (entry, backup) -> rows[id(entry)]?.takeIf { it.isNotEmpty() }?.let { write(entry, backup, it) } }

/**
 * A backup's merge groups as lists of member refs. [ref] returns null for a member whose row has gone,
 * and a group left with fewer than two members is dropped, since one source is not a group.
 */
suspend fun <R> mergeGroupRefs(memberships: Map<Long, Long>, ref: suspend (Long) -> R?): List<List<R>> =
    memberships.entries
        .groupBy({ it.value }, { it.key })
        .values
        .mapNotNull { memberIds -> memberIds.mapNotNull { ref(it) }.takeIf { it.size >= 2 } }

/**
 * Restores [batch] in one transaction, then entry by entry if that fails, and returns each entry that
 * failed with its error. SQLDelight fails an enclosing transaction when a nested one fails, so a catch
 * inside the batch cannot contain one bad entry: the whole batch rolls back and would be lost. That
 * rollback is silent when the nested write caught its own error, so each run has to check for it.
 */
suspend fun <B> restoreBatch(
    batch: List<B>,
    transactions: Transactions,
    restore: suspend (B) -> Unit,
): List<Pair<B, Exception>> {
    try {
        transactions.runThrowingOnRollback {
            batch.forEach {
                currentCoroutineContext().ensureActive()
                restore(it)
            }
        }
        return emptyList()
    } catch (e: Exception) {
        // A cancelled restore stops here rather than being retried as if one entry had failed.
        currentCoroutineContext().ensureActive()
        logcat("BackupRestorer", LogPriority.WARN) { "Batch restore failed, retrying entry by entry\n${e.asLog()}" }
    }
    return batch.mapNotNull { entry ->
        currentCoroutineContext().ensureActive()
        try {
            transactions.runThrowingOnRollback { restore(entry) }
            null
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            entry to e
        }
    }
}
