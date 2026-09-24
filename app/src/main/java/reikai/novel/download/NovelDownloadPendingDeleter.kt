package reikai.novel.download

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.novel.model.NovelChapter

/**
 * Novel chapters queued for deletion when the reader closes, the novel twin of Mihon's
 * [eu.kanade.tachiyomi.data.download.DownloadPendingDeleter]. Persisted so a queue a process death cut
 * short is deleted on the next close. Only ids are kept; the rows are read back when the queue runs.
 * Its own file rather than a preference, so a backup never carries it to another device.
 */
@Inject
@SingleIn(AppScope::class)
class NovelDownloadPendingDeleter(context: Context) {

    private val preferences = context.getSharedPreferences("novel_chapters_to_delete", Context.MODE_PRIVATE)

    @Synchronized
    fun addChapters(chapters: List<NovelChapter>) {
        preferences.edit { chapters.forEach { putBoolean(it.id.toString(), true) } }
    }

    /** The queued chapter ids, emptying the queue. */
    @Synchronized
    fun takePendingChapterIds(): List<Long> {
        val ids = preferences.all.keys.mapNotNull { it.toLongOrNull() }
        preferences.edit { clear() }
        return ids
    }
}
