package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import tachiyomi.domain.manga.model.Manga
import java.io.Serializable
import kotlin.time.Instant

/**
 * Domain mirror of the `novels` SQLDelight table. Held disjoint from [tachiyomi.domain.manga.model.Manga]
 * because the source-id space differs (lnreader plugin.id is a [String], not a [Long]) and the
 * content is text rather than images, so manga-only fields (scanlators, reading mode) don't apply.
 * [viewerFlags] carries the per-novel reader orientation and rendering mode.
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
    /** When the novel was added to the library, null while it is not in it (as [Manga.favoriteAt]). */
    val favoriteAt: Instant?,
    val lastUpdate: Instant?,
    val initialized: Boolean,
    val chapterFlags: Long,
    val updateStrategy: UpdateStrategy,
    val coverLastModified: Instant?,
    /**
     * For paged-novel sources (Royal Road volumes, some Japanese sources) where a single novel's
     * chapter list spans multiple endpoints. Defaults to 1 for single-page novels; the update job
     * re-fetches `oldTotalPages + 1` through this value to discover new chapters on later pages.
     */
    val totalPages: Long,
    /** Free-text user note shown/edited on the details screen (the novel twin of `Manga.notes`, type only). */
    val notes: String,
    /**
     * Reader viewer-flags bitmask, the novel twin of `Manga.viewerFlags`, type only: the
     * `ReaderOrientation` bits and the novel's own `NovelRenderingMode` bits (novels have no reading mode);
     * 0 in either means "follow the global setting". See `readerOrientation`.
     */
    val viewerFlags: Long,
    /** When smart update next fetches this novel; null until one is predicted. */
    val nextUpdate: Instant? = null,
    /** The release interval in days that [nextUpdate] was predicted from, negative when the user set it. */
    val fetchInterval: Int = 0,
) : Serializable {

    val favorite: Boolean
        get() = favoriteAt != null

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
            favoriteAt = null,
            lastUpdate = null,
            initialized = false,
            chapterFlags = 0L,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            coverLastModified = null,
            totalPages = 1L,
            notes = "",
            viewerFlags = 0L,
        )
    }
}
