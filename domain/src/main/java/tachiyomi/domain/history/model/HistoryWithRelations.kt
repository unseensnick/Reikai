package tachiyomi.domain.history.model

import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val title: String,
    val chapterNumber: Double,
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
    // RK --> the recents read lane acts on the chapter this row names, so it carries that chapter's
    // own state and the four values its download state is looked up by. Upstream's History screen
    // shows a number and a time, which is why none of these were selected before.
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val sourceId: Long,
    /**
     * The title as stored, which the display-only custom-title overlay does not rewrite. A download
     * folder is named from this one, so looking a download up by [title] finds nothing as soon as
     * the user renames the entry.
     */
    val storedTitle: String,
    // RK <--
)
