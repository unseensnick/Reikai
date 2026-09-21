package reikai.domain.track.source

import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.entry.EntryId
import tachiyomi.core.common.util.system.logcat

/** An entry as a source's own tracker sees it, read fresh from the library when a call is made. */
class TrackedEntry(
    val tracker: SourceTracker,
    val trackerName: String,
    val manga: SManga,
    /** Whether the entry is in the library now, which an add or remove is checked against. */
    val favorite: Boolean,
    val chapters: List<TrackedChapter>,
    /** The entry's category names, without the uncategorized one. */
    val categories: List<String>,
)

class TrackedChapter(val id: Long, val chapter: SChapter, val read: Boolean, val number: Double)

/** A chapter whose read state was just written, with the state it had before. */
data class ChapterWrite(val entry: EntryId, val id: Long, val wasRead: Boolean)

/** Reads an entry for its source's tracker; null when the entry is gone or its source does not track. */
fun interface TrackedEntryLoader {
    suspend fun load(entry: EntryId): TrackedEntry?
}

/**
 * Tells an extension that tracks reading on its own site what the user did, for both content types.
 * Ported from tsundoku's `SourceTrackerDispatcher`, whose behaviour extensions are written against:
 * chapter events wait [DEBOUNCE_MS] per entry and merge while they agree, an event of the other kind
 * starts over. Unlike tsundoku, an add or remove waits too and goes only if the library still agrees,
 * so a rolled-back add sends nothing and categories filed just after it go with it; an add and a remove
 * inside the wait cancel out. Each call's failure is reported and stops nothing else.
 */
class SourceTrackerKernel(
    private val scope: CoroutineScope,
    private val loader: TrackedEntryLoader,
    private val onFailure: (trackerName: String, error: Throwable) -> Unit,
) {

    private class Pending(val read: Boolean, val chapterIds: Set<Long>, val job: Job)

    private val pending = HashMap<EntryId, Pending>()

    private class PendingFavorite(val favorite: Boolean, val job: Job)

    private val pendingFavorites = HashMap<EntryId, PendingFavorite>()

    /** Both content types' read writes land here, so which chapters count is decided once. */
    fun readStateWritten(read: Boolean, chapters: List<ChapterWrite>) {
        // An unread counts only the chapters that were read, not ones merely started.
        chapters.filter { read || it.wasRead }.groupBy { it.entry }.forEach { (entry, changed) ->
            chaptersChanged(entry, changed.map { it.id }, read)
        }
    }

    fun chaptersChanged(entry: EntryId, chapterIds: Collection<Long>, read: Boolean) {
        if (chapterIds.isEmpty()) return
        synchronized(pending) {
            val previous = pending.remove(entry)
            previous?.job?.cancel()
            val ids = if (previous?.read == read) previous.chapterIds + chapterIds else chapterIds.toSet()
            val job = scope.launch {
                delay(DEBOUNCE_MS)
                // A job cancelled while it waited for the lock has had its ids merged into a newer one.
                synchronized(pending) {
                    if (pending[entry]?.chapterIds !== ids) return@launch
                    pending.remove(entry)
                }
                dispatchChapters(entry, ids, read)
            }
            pending[entry] = Pending(read, ids, job)
        }
    }

    fun favoriteChanged(entry: EntryId, favorite: Boolean) {
        synchronized(pendingFavorites) {
            val previous = pendingFavorites.remove(entry)
            previous?.job?.cancel()
            // An add and a remove inside the wait: the site never heard the first, and the library is back.
            if (previous != null && previous.favorite != favorite) return
            lateinit var mine: PendingFavorite
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(DEBOUNCE_MS)
                synchronized(pendingFavorites) {
                    if (pendingFavorites[entry] !== mine) return@launch
                    pendingFavorites.remove(entry)
                }
                dispatchFavorite(entry, favorite)
            }
            mine = PendingFavorite(favorite, job)
            pendingFavorites[entry] = mine
            job.start()
        }
    }

    private suspend fun dispatchFavorite(entry: EntryId, favorite: Boolean) {
        val tracked = loadOrNull(entry) ?: return
        if (tracked.favorite != favorite) return
        // The gates are the extension's code too, so they run inside the guard.
        reporting(tracked) {
            if (!tracker.supportsFavoritesTracking) return@reporting
            if (favorite) {
                tracker.onFavorited(manga, categories)
            } else {
                tracker.onUnfavorited(manga, categories)
            }
        }
    }

    /**
     * A migration from [from] to [to] went through. The target is favorited and, when read state was
     * carried, its read chapters are read, as tsundoku does; on a [replace] the old entry is unfavorited.
     */
    fun migrated(from: EntryId, to: EntryId, replace: Boolean, carriedChapters: Boolean) {
        if (replace) favoriteChanged(from, favorite = false)
        favoriteChanged(to, favorite = true)
        if (!carriedChapters) return
        scope.launch {
            val read = loadOrNull(to)?.chapters?.filter { it.read }?.map { it.id }.orEmpty()
            chaptersChanged(to, read, read = true)
        }
    }

    private suspend fun dispatchChapters(entry: EntryId, chapterIds: Set<Long>, read: Boolean) {
        val tracked = loadOrNull(entry) ?: return
        val changed = tracked.chapters.filter { it.id in chapterIds }
        if (changed.isEmpty()) return
        val all = tracked.chapters.map { it.chapter }
        reporting(tracked) {
            if (!tracker.supportsChapterTracking) return@reporting
            if (read) {
                tracker.onChaptersRead(manga, changed.map { it.chapter }, all, categories)
                return@reporting
            }
            // Unreading below a chapter still read moves the site back to that one, as tsundoku does,
            // rather than telling it the entry was unread.
            val highestRead = chapters.filter { it.read && it.number > 0 }.maxByOrNull { it.number }
            if (highestRead != null) {
                tracker.onChaptersRead(manga, listOf(highestRead.chapter), all, categories)
            } else {
                tracker.onChaptersUnread(manga, changed.map { it.chapter }, all, categories)
            }
        }
    }

    // Nothing thrown here may leave the scope: an uncaught failure there takes the app down.
    private suspend fun loadOrNull(entry: EntryId): TrackedEntry? = try {
        loader.load(entry)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Could not read $entry for its source's tracker" }
        null
    }

    private suspend fun reporting(tracked: TrackedEntry, call: suspend TrackedEntry.() -> Unit) {
        try {
            tracked.call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailure(tracked.trackerName, e)
        }
    }

    companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
