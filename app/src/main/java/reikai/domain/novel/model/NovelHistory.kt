package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import reikai.data.coil.NovelCover

/**
 * One History-tab novel row: a novel's most-recently-read chapter plus its cover. Novel twin of
 * [tachiyomi.domain.history.model.HistoryWithRelations]. [readAt] is epoch millis (the novel side
 * stores history times as plain Long, no Date adapter); nullable to mirror the manga model, though the
 * feed query only returns rows with readAt > 0.
 */
@Immutable
data class NovelHistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val novelId: Long,
    val title: String,
    val chapterNumber: Double,
    val readAt: Long?,
    val readDuration: Long,
    val coverData: NovelCover,
)

/**
 * Reader write payload (novel twin of [tachiyomi.domain.history.model.HistoryUpdate]). [readAt] is
 * epoch millis; [sessionReadDuration] is the time spent in the chapter this session, accumulated into
 * `time_read` on upsert.
 */
data class NovelHistoryUpdate(
    val chapterId: Long,
    val readAt: Long,
    val sessionReadDuration: Long,
)
