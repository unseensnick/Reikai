package reikai.domain.novel.track

import android.content.Context
import androidx.core.content.edit
import eu.kanade.domain.track.store.DelayedTrackingStore.DelayedTrackingItem
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Novel twin of [eu.kanade.domain.track.store.DelayedTrackingStore]. Separate prefs file because the
 * queue is keyed by track id, and novel track ids and manga track ids share the same id space, so a
 * shared file would collide.
 */
class NovelDelayedTrackingStore(context: Context) {

    private val preferences = context.getSharedPreferences("novel_tracking_queue", Context.MODE_PRIVATE)

    fun add(trackId: Long, lastChapterRead: Double) {
        val previousLastChapterRead = preferences.getFloat(trackId.toString(), 0f)
        if (lastChapterRead > previousLastChapterRead) {
            logcat(LogPriority.DEBUG) { "Queuing novel track item: $trackId, last chapter read: $lastChapterRead" }
            preferences.edit {
                putFloat(trackId.toString(), lastChapterRead.toFloat())
            }
        }
    }

    fun remove(trackId: Long) {
        preferences.edit {
            remove(trackId.toString())
        }
    }

    fun getItems(): List<DelayedTrackingItem> {
        return preferences.all.mapNotNull {
            DelayedTrackingItem(
                trackId = it.key.toLong(),
                lastChapterRead = it.value.toString().toFloat(),
            )
        }
    }
}
