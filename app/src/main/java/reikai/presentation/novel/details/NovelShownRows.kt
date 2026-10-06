package reikai.presentation.novel.details

import reikai.domain.merge.GroupMarks
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.sortedAndFiltered
import reikai.presentation.details.HiddenChapterView
import reikai.presentation.details.resolveHiddenChapterView

/**
 * The rows a novel's chapter list shows out of [chapters], in its order: hidden ones dropped unless they
 * are being shown, then the filters and sort. The list and Mark previous as read both take their rows
 * from here, so, as on manga, the action never reaches a chapter the list leaves out.
 */
fun novelShownRows(
    chapters: List<NovelChapter>,
    novel: Novel,
    prefs: NovelPreferences,
    hiddenKeys: Set<String>,
    showHiddenRequested: Boolean,
    keyOf: (NovelChapter) -> String,
    downloadedChapterIds: Set<Long>,
    marks: GroupMarks,
    downloadedOnly: Boolean,
): HiddenChapterView<NovelChapter> {
    val view = resolveHiddenChapterView(chapters, hiddenKeys, showHiddenRequested, keyOf)
    val shown = view.visible.sortedAndFiltered(novel, prefs, downloadedChapterIds, marks, downloadedOnly)
    return view.copy(visible = shown)
}
