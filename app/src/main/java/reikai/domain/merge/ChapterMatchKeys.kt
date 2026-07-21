package reikai.domain.merge

import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.MANGADEX_IDS
import exh.source.getMainSource
import tachiyomi.domain.source.service.SourceManager

/**
 * The cross-source identity of a manga chapter, as stored in `chapter_match_key`. Two chapters from
 * different sources share a key when they are the same chapter, which is what lets a merged entry
 * count them once.
 *
 * Mirrors the rule [reikai.domain.manga.ChapterAggregation] applies when it stitches the merged
 * chapter list, so the library badge and that list agree on what "the same chapter" means. The novel
 * equivalent is `NovelChapterAggregation.matchKey`, which keys on the normalized title instead.
 */
object ChapterMatchKeys {

    /**
     * Null when the chapter has no cross-source identity and must never dedup: an unrecognized
     * number (which cannot be matched against anything), or a gallery source's chapter (each is a
     * standalone work, and every gallery source numbers its first one 1, so keying by number would
     * collapse different works into one).
     *
     * Narrowed to Float for the same reason the aggregation does it: a source that reports its own
     * number hands back a 32-bit float while a parsed one is a double, and the two differ by about
     * 2.4e-8, so an exact double key would leave the same chapter duplicated across sources.
     */
    fun manga(chapterNumber: Double, isGallerySource: Boolean): String? = when {
        isGallerySource -> null
        // Mirrors Chapter.isRecognizedNumber, which the row does not store.
        chapterNumber < 0.0 -> null
        else -> chapterNumber.toFloat().toString()
    }

    /**
     * Whether a source's chapters are each a standalone work rather than an instalment.
     *
     * True gallery sources all implement [NamespaceSource], but so does the enhanced MangaDex, which
     * has normal sequential chapters that must dedup like any other source, so it is excluded by id.
     * There is no clean positive id-set for every gallery, since installed extensions vary.
     */
    fun isGallerySource(sourceId: Long, sourceManager: SourceManager): Boolean =
        sourceId !in MANGADEX_IDS && sourceManager.get(sourceId)?.getMainSource<NamespaceSource>() != null
}
