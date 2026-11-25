package reikai.presentation.library

import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import reikai.domain.entry.EntryId
import reikai.presentation.library.novels.NovelLibraryRows
import reikai.presentation.reader.novelResumeIntent

/**
 * Where a library row's Continue reading opens, dispatched on the row's own type, so a mixed list resumes
 * each row in its reader. The library and a Statistics series list both call it; null is nothing to read.
 */
@Inject
class LibraryResume(
    private val mangaLibraryRows: MangaLibraryRows,
    private val novelLibraryRows: NovelLibraryRows,
) {
    suspend fun intent(context: Context, item: LibraryItem): Intent? = when (item.entryId) {
        is EntryId.Manga -> mangaLibraryRows.nextUnreadChapter(item.libraryManga.manga)
            ?.let { ReaderActivity.newIntent(context, it.mangaId, it.id) }
        is EntryId.Novel -> novelLibraryRows.resumeChapter(item.id)
            ?.let { novelResumeIntent(context, it.novelId, it.id) }
    }
}
