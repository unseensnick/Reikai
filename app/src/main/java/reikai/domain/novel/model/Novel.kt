package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.domain.entry.EntryId
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
     * [ReaderOrientation] bits are used (novels have no reading mode); 0 means "follow the global
     * default orientation". See [readerOrientation].
     */
    val viewerFlags: Long,
    /**
     * Edit-count bumped by the `update_novel_version` DB trigger on real detail changes (the novel
     * twin of `Manga.version`). Backup restore compares it to keep the newer copy rather than
     * blindly overwriting; see `NovelRestorer`.
     */
    val version: Long,
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
            version = 0L,
        )
    }
}

/**
 * The per-novel reader orientation bits (the novel twin of `Manga.readerOrientation`). 0 = DEFAULT,
 * which the reader resolves to the global default orientation.
 */
val Novel.readerOrientation: Long
    get() = viewerFlags and ReaderOrientation.MASK.toLong()

/**
 * True when the user set a custom cover for this novel. The cover lives in the shared [CoverCache]
 * under the entry's own namespaced name (so it can't collide with a same-id manga); the novel twin of
 * `Manga.hasCustomCover`.
 */
fun Novel.hasCustomCover(coverCache: CoverCache): Boolean =
    coverCache.getCustomCoverFile(EntryId.Novel(id)).exists()
