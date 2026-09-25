package eu.kanade.tachiyomi.ui.reader.chapter

import tachiyomi.domain.chapter.model.Chapter

/**
 * Reikai: a chapter row shown in the in-reader chapter list dialog. Ported from Komikku.
 */
data class ReaderChapterItem(
    val chapter: Chapter,
    // The chapter's source name for a merged group (null when not merged), shown as the row's
    // subtitle so a unified list makes clear which source each chapter comes from.
    val sourceName: String? = null,
)
