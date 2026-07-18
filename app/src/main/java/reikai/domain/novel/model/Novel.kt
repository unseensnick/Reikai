package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.domain.entry.EntryId
import reikai.domain.entry.coverCacheKey
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable

/**
 * Domain mirror of the `novels` SQLDelight table. Held disjoint from [tachiyomi.domain.manga.model.Manga]
 * because the source-id space differs (lnreader plugin.id is a [String], not a [Long]) and the
 * content is text rather than images, so most manga-only fields (scanlators, fetch interval) don't
 * apply. [viewerFlags] is the one carried over: it stores the per-novel reader orientation only.
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
    /**
     * Denormalized last-read timestamp, written from the chapter-mark-read path so the LastRead
     * library sort doesn't pay a JOIN-per-row. Null when the novel has never been opened.
     */
    val lastReadAt: Long?,
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
            lastReadAt = null,
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
 * keyed by the negated id (so it can't collide with a same-id manga); the novel twin of
 * `Manga.hasCustomCover`.
 */
fun Novel.hasCustomCover(coverCache: CoverCache = Injekt.get()): Boolean =
    coverCache.getCustomCoverFile(EntryId.Novel(id).coverCacheKey()).exists()
