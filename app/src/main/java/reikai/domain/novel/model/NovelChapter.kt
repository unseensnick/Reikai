package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import java.io.Serializable

/**
 * Domain mirror of the `novel_chapters` table. Two divergences from manga chapter semantics:
 *
 * - No `scanlator` (novels don't have scanlator groups).
 * - [lastTextProgress] replaces the manga side's `lastPageRead`. A hundredths scroll percent
 *   (0..10000) so the text reader can resume mid-chapter.
 */
@Immutable
data class NovelChapter(
    val id: Long,
    val novelId: Long,
    val url: String,
    val name: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastTextProgress: Long,
    val chapterNumber: Double,
    val sourceOrder: Long,
    val dateFetch: Long,
    val dateUpload: Long,
    /**
     * Volume / section / arc label this chapter belongs to (e.g. "Volume 3"). Populated for
     * paged sources; empty string when the source doesn't expose it.
     */
    val page: String,
    /**
     * True when the chapter's text was downloaded for offline reading. The on-disk HTML file is
     * the read-back source of truth; this flag is the fast signal the chapter list uses.
     */
    val isDownloaded: Boolean,
) : Serializable
