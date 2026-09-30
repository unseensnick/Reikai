package reikai.domain.novel

import eu.kanade.core.util.insertSeparators
import reikai.domain.merge.ChapterGap
import reikai.domain.novel.model.NovelChapter

/**
 * A row in the novel details chapter list: either a chapter or a "N missing chapters" separator.
 * Twin of manga's `ChapterList.Item` / `ChapterList.MissingCount`, pinned by [ChapterGap.betweenRows].
 */
sealed interface NovelChapterListEntry {
    data class Item(val chapter: NovelChapter) : NovelChapterListEntry
    data class Missing(val id: String, val count: Int) : NovelChapterListEntry
}

/** The display list, with a [NovelChapterListEntry.Missing] wherever [ChapterGap.betweenRows] marks a gap. */
fun buildNovelChapterListEntries(
    chapters: List<NovelChapter>,
    sortDescending: Boolean,
): List<NovelChapterListEntry> =
    chapters.map(NovelChapterListEntry::Item).insertSeparators { before, after ->
        ChapterGap.betweenRows(before?.chapter?.toGapNeighbour(), after?.chapter?.toGapNeighbour(), sortDescending)
            .takeIf { it > 0 }
            ?.let { NovelChapterListEntry.Missing(id = "${before?.chapter?.id}-${after?.chapter?.id}", count = it) }
    }

/** A merged list's neighbours can come from different sources, so the owning novel travels with the
 *  number the gap is computed from. */
private fun NovelChapter.toGapNeighbour() = ChapterGap.Neighbour(chapterNumber, name, novelId)

/** The header's total, over the same rule the inline markers use, so the two cannot disagree. */
fun novelMissingChapterCount(chapters: List<NovelChapter>, sortDescending: Boolean): Int =
    ChapterGap.total(chapters.map { it.toGapNeighbour() }, sortDescending)
