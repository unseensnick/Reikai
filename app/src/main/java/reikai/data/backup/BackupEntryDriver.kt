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
 * up and which optional parts each carries; the type owns what a part holds, since manga and novels are
 * separate messages in a frozen wire format and cannot share fields. Each part writes onto [B] itself.
 */
interface BackupEntryParts<E, B> {
    suspend fun favorites(): List<E>

    /** Series outside the library that still carry read progress, which "All read entries" keeps. */
    suspend fun readNotInLibrary(): List<E>

    /** The series' own fields, plus any part the type writes whatever the options say. */
    suspend fun base(entry: E): B

    suspend fun chapters(entry: E, backup: B)

    suspend fun categories(entry: E, backup: B)

    suspend fun tracking(entry: E, backup: B)

    suspend fun history(entry: E, backup: B)

    suspend fun customInfo(entry: E, backup: B)
}

/** Every series these options back up, one at a time, so a large library is never held whole. */
fun <E, B> BackupOptions.backupEntries(parts: BackupEntryParts<E, B>): Flow<B> = flow {
    val readOnly = if (readEntries) parts.readNotInLibrary() else emptyList()
    for (entry in parts.favorites() + readOnly) {
        emit(backupEntry(entry, parts))
        yield()
    }
}

suspend fun <E, B> BackupOptions.backupEntry(entry: E, parts: BackupEntryParts<E, B>): B {
    val backup = parts.base(entry)
    if (chapters) parts.chapters(entry, backup)
    if (categories) parts.categories(entry, backup)
    if (tracking) parts.tracking(entry, backup)
    if (history) parts.history(entry, backup)
    if (customInfo) parts.customInfo(entry, backup)
    return backup
}

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
 * inside the batch cannot contain one bad entry: the whole batch rolls back and would be lost.
 */
suspend fun <B> restoreBatch(
    batch: List<B>,
    transactions: Transactions,
    restore: suspend (B) -> Unit,
): List<Pair<B, Exception>> {
    try {
        transactions.run {
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
            restore(entry)
            null
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            entry to e
        }
    }
}
