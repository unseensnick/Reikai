package exh.pagepreview

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.domain.chapter.model.Chapter

/**
 * The chapter gallery [mangaId]'s page previews are pages of: its own first by source order, read from its own
 * chapters by [chaptersOf], never a merged series' list, which renumbers source order across its sources.
 */
suspend fun previewChapterOf(mangaId: Long, chaptersOf: suspend (Long) -> List<Chapter>): Chapter? =
    chaptersOf(mangaId).minByOrNull { it.sourceOrder }

/** Opens the reader at preview [page] of [chapter], from the details page and the More previews page alike. */
fun Context.openPreviewPage(chapter: Chapter, page: Int) {
    startActivity(ReaderActivity.newIntent(this, chapter.mangaId, chapter.id, page))
}
