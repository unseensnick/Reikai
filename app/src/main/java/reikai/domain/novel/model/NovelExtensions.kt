package reikai.domain.novel.model

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import reikai.domain.entry.EntryId

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
