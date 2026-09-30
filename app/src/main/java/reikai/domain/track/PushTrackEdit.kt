package reikai.domain.track

import android.content.Context
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import reikai.presentation.track.trackerErrorMessage
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Push one edited field to the tracker and persist the row through [persist], the content type's own
 * table. Both the manga and the novel writer end here, so a server binding nobody has started is kept
 * from its 0 ([sendsProgressTo]) on both, and a failure is toasted rather than thrown.
 */
suspend fun Tracker.pushTrackEdit(context: Context, track: DbTrack, persist: suspend (DbTrack) -> Unit): Unit =
    withIOContext {
        try {
            if (sendsProgressTo(this@pushTrackEdit, track.last_chapter_read)) update(track)
            persist(track)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to update remote track data id=$id" }
            withUIContext { context.toast(context.trackerErrorMessage(name, e)) }
        }
    }
