package reikai.domain.manga

import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.MANGADEX_IDS
import exh.source.getMainSource
import tachiyomi.domain.source.service.SourceManager

object GallerySources {

    /**
     * Whether a source's chapters are each a standalone work rather than an instalment.
     *
     * True gallery sources all implement [NamespaceSource], but so does the enhanced MangaDex, which
     * has normal sequential chapters that must dedup like any other source, so it is excluded by id.
     * There is no clean positive id-set for every gallery, since installed extensions vary.
     */
    suspend fun isGallerySource(sourceId: Long, sourceManager: SourceManager): Boolean =
        sourceId !in MANGADEX_IDS && sourceManager.get(sourceId)?.getMainSource<NamespaceSource>() != null
}
