package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import java.io.Serializable
import kotlin.time.Instant

/**
 * Domain mirror of the `novel_chapters` table. [lastTextProgress] replaces the manga side's
 * `lastPageRead`: scroll progress in hundredths of a percent (0..10000), which the reader writes and
 * resumes at that precision.
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
    val dateFetch: Instant,
    val dateUpload: Instant?,
    /**
     * Volume / section / arc label this chapter belongs to (e.g. "Volume 3"). Populated for
     * paged sources; empty string when the source doesn't expose it.
     */
    val page: String,
    /** The translation group the source names, as manga's `scanlator`; null when it names none. */
    val scanlator: String? = null,
    /**
     * What number recognition starts from: the plugin's own number, -1 for none. Null for a row written
     * without one (a restore), which stores its [chapterNumber] in its place.
     */
    val rawChapterNumber: Double? = null,
) : Serializable
