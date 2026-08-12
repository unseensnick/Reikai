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
    // The recents read lane acts on the chapter this row names, so it carries that chapter's own
    // state and the values its download state is looked up by. No scanlator on this side, and
    // progress is a hundredths scroll percent where the manga twin counts pages.
    val chapterName: String,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastTextProgress: Long,
    val source: String,
    /**
     * The title as stored, which the display-only custom-title overlay does not rewrite. A download
     * folder is named from this one, so looking a download up by [title] finds nothing as soon as
     * the user renames the entry.
     */
    val storedTitle: String,
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
