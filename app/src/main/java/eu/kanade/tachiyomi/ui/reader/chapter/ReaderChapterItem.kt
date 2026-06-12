package eu.kanade.tachiyomi.ui.reader.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.time.format.DateTimeFormatter

/**
 * Reikai (R-feature): a chapter row shown in the in-reader chapter list dialog. Ported from Komikku.
 */
data class ReaderChapterItem(
    val chapter: Chapter,
    val manga: Manga,
    val isCurrent: Boolean,
    val dateFormat: DateTimeFormatter,
)
