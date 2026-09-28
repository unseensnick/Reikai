package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import java.io.Serializable

/**
 * Domain mirror of the `novels` SQLDelight table. Held disjoint from [tachiyomi.domain.manga.model.Manga]
 * because the source-id space differs (lnreader plugin.id is a [String], not a [Long]) and the
 * content is text rather than images, so manga-only fields (scanlators, reading mode) don't apply.
 * [viewerFlags] carries only the per-novel reader orientation.
 */
@Immutable
data class Novel(
    val id: Long,
    val source: String,
    val url: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    val favorite: Boolean,
    val lastUpdate: Long,
    val initialized: Boolean,
    val chapterFlags: Long,
    val dateAdded: Long,
    val updateStrategy: UpdateStrategy,
    val coverLastModified: Long,
    /**
     * For paged-novel sources (Royal Road volumes, some Japanese sources) where a single novel's
     * chapter list spans multiple endpoints. Defaults to 1 for single-page novels; the update job
     * re-fetches `oldTotalPages + 1` through this value to discover new chapters on later pages.
     */
    val totalPages: Long,
    /** Free-text user note shown/edited on the details screen (the novel twin of `Manga.notes`). */
    val notes: String,
    /**
     * Reader viewer-flags bitmask, the novel twin of `Manga.viewerFlags`. Currently only the
     * `ReaderOrientation` bits are used (novels have no reading mode); 0 means "follow the global
     * default orientation". See `readerOrientation`.
     */
    val viewerFlags: Long,
    /** When smart update next fetches this novel, in epoch millis; 0 until one is predicted. */
    val nextUpdate: Long = 0L,
    /** The release interval in days that [nextUpdate] was predicted from, negative when the user set it. */
    val fetchInterval: Int = 0,
) : Serializable {

    companion object {
        fun create() = Novel(
            id = -1L,
            source = "",
            url = "",
            title = "",
            author = null,
            artist = null,
            description = null,
            genre = null,
            status = 0L,
            thumbnailUrl = null,
            favorite = false,
            lastUpdate = 0L,
            initialized = false,
            chapterFlags = 0L,
            dateAdded = 0L,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            coverLastModified = 0L,
            totalPages = 1L,
            notes = "",
            viewerFlags = 0L,
        )
    }
}
