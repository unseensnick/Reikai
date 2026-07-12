package reikai.domain.novel

import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.chapter.service.calculateChapterGap
import kotlin.math.floor

/**
 * A row in the novel details chapter list: either a chapter or a "N missing chapters" separator
 * inserted between two chapters whose numbers skip one or more integers. The novel twin of manga's
 * `ChapterList.Item` / `ChapterList.MissingCount` (see MangaScreenModel.chapterListItems).
 */
sealed interface NovelChapterListEntry {
    data class Item(val chapter: NovelChapter) : NovelChapterListEntry
    data class Missing(val id: String, val count: Int) : NovelChapterListEntry
}

/**
 * Build the display list, inserting a [NovelChapterListEntry.Missing] wherever consecutive chapter
 * numbers leave a gap. Mirrors manga's `insertSeparators` swap logic: [sortDescending] flips which
 * neighbour is the higher number, the leading gap (ascending, before the first) and trailing gap
 * (descending, after the last) use `floor(number) - 1`, and an unrecognized number (< 0) yields no
 * separator (the Double [calculateChapterGap] overload returns 0 for it).
 */
fun buildNovelChapterListEntries(
    chapters: List<NovelChapter>,
    sortDescending: Boolean,
): List<NovelChapterListEntry> {
    val result = ArrayList<NovelChapterListEntry>(chapters.size)
    for (i in 0..chapters.size) {
        val before = chapters.getOrNull(i - 1)
        val after = chapters.getOrNull(i)
        missingSeparator(before, after, sortDescending)?.let(result::add)
        if (after != null) result.add(NovelChapterListEntry.Item(after))
    }
    return result
}

private fun missingSeparator(
    before: NovelChapter?,
    after: NovelChapter?,
    sortDescending: Boolean,
): NovelChapterListEntry.Missing? {
    val (lowerChapter, higherChapter) = if (sortDescending) after to before else before to after
    if (higherChapter == null) return null

    val count = if (lowerChapter == null) {
        floor(higherChapter.chapterNumber).toInt().minus(1).coerceAtLeast(0)
    } else {
        calculateChapterGap(higherChapter.chapterNumber, lowerChapter.chapterNumber)
    }
    return count.takeIf { it > 0 }?.let {
        NovelChapterListEntry.Missing(
            id = "${lowerChapter?.id}-${higherChapter.id}",
            count = it,
        )
    }
}
