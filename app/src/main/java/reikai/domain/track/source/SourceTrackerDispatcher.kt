package reikai.domain.track.source

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import logcat.LogPriority
import reikai.domain.entry.EntryId
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * The app's one [SourceTrackerKernel], called from the places a user reads, unreads, adds or removes an
 * entry. It lives with the app rather than a screen, since a debounced call outlives the screen it
 * came from.
 */
@Inject
@SingleIn(AppScope::class)
class SourceTrackerDispatcher(
    private val context: Context,
    private val trackPreferences: TrackPreferences,
    // Deferred: the interactors that call this sit below the source managers it reads through, and a
    // source manager's graph reaching one of them back would be a cycle.
    entries: () -> SourceTrackedEntries,
) {

    private val loader = TrackedEntryLoader { entries().load(it) }

    private val lastToast = HashMap<String, Long>()

    private val kernel = SourceTrackerKernel(CoroutineScope(SupervisorJob() + Dispatchers.IO), loader) { name, error ->
        logcat(LogPriority.ERROR, error) { "$name could not sync reading to its site" }
        // One toast per site while it keeps failing, since a bulk removal offline fails once per entry.
        val now = System.currentTimeMillis()
        val shown = synchronized(lastToast) {
            (now - (lastToast[name] ?: 0L) >= TOAST_COOLDOWN_MS).also { if (it) lastToast[name] = now }
        }
        if (shown) {
            launchUI {
                context.toast(context.stringResource(MR.strings.source_tracker_failed, name, error.message.orEmpty()))
            }
        }
    }

    fun readStateWritten(read: Boolean, chapters: List<ChapterWrite>) = kernel.readStateWritten(read, chapters)

    fun favoriteChanged(entry: EntryId, favorite: Boolean) = kernel.favoriteChanged(entry, favorite)

    /** Called by both migrate engines once the favorite swap has committed. */
    fun migrated(from: EntryId, to: EntryId, replace: Boolean, carriedChapters: Boolean) {
        if (!trackPreferences.sourceTrackerOnMigration.get()) return
        kernel.migrated(from, to, replace, carriedChapters)
    }

    private companion object {
        const val TOAST_COOLDOWN_MS = 10_000L
    }
}
