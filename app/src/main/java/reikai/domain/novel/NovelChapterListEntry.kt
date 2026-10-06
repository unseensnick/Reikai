package reikai.domain.novel

import reikai.domain.merge.ChapterGap
import reikai.domain.novel.model.NovelChapter

/**
 * A row in the novel details chapter list: either a chapter or a "N missing chapters" separator.
 * Twin of manga's `ChapterList.Item` / `ChapterList.MissingCount`, pinned by [ChapterGap.withMarkers].
 */
sealed interface NovelChapterListEntry {
    data class Item(val chapter: NovelChapter) : NovelChapterListEntry
    data class Missing(val id: String, val count: Int) : NovelChapterListEntry
}

/** The display list, with a [NovelChapterListEntry.Missing] wherever [ChapterGap.withMarkers] marks a gap. */
fun buildNovelChapterListEntries(
    chapters: List<NovelChapter>,
    sortDescending: Boolean,
    present: ChapterGap.Present,
    isHidden: (NovelChapter) -> Boolean,
): List<NovelChapterListEntry> =
    ChapterGap.withMarkers(
        chapters,
        neighbourOf = { it.toGapNeighbour() },
        isHidden = isHidden,
        present = present,
        descending = sortDescending,
        row = NovelChapterListEntry::Item,
    ) { before, after, count -> NovelChapterListEntry.Missing(id = "${before?.id}-${after?.id}", count = count) }

/** A merged list's neighbours can come from different sources, so the owning novel travels with the
 *  number the gap is computed from. */
private fun NovelChapter.toGapNeighbour() = ChapterGap.Neighbour(chapterNumber, name, novelId)

/** The numbers a novel's gaps are counted against: every chapter of [this], as [ChapterGap.Present] asks. */
fun Iterable<NovelChapter>.gapPresent(): ChapterGap.Present =
    ChapterGap.Present.of(this, { it.novelId }, { it.chapterNumber })

/** The header's total, over the same rule the inline markers use, so the two cannot disagree. */
fun novelMissingChapterCount(
    chapters: List<NovelChapter>,
    sortDescending: Boolean,
    present: ChapterGap.Present,
    isHidden: (NovelChapter) -> Boolean,
): Int = ChapterGap.total(chapters, { it.toGapNeighbour() }, isHidden, present, sortDescending)
