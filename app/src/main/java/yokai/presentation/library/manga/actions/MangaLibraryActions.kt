package yokai.presentation.library.manga.actions

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.chapter.models.ChapterUpdate

/**
 * Compose-side ports of the legacy `LibraryPresenter` selection actions, kept as pure suspend
 * functions so the screen model can wire them without leaking Injekt / Koin into the file.
 * Functions mirror legacy semantics so both code paths converge on the same persisted state:
 *
 *  - [share]: faithful port of `LibraryPresenter.getMangaUrls` at `LibraryPresenter.kt:1368`
 *  - [downloadUnread]: faithful port of `LibraryPresenter.downloadUnread` at `LibraryPresenter.kt:1677`
 *  - [markReadStatus]: faithful port of `LibraryPresenter.markReadStatus` at `LibraryPresenter.kt:1691`
 *  - [undoMarkReadStatus]: faithful port of `LibraryPresenter.undoMarkReadStatus` at `LibraryPresenter.kt:1712`
 *  - [confirmMarkReadStatus]: faithful port of `LibraryPresenter.confirmMarkReadStatus` at `LibraryPresenter.kt:1727`
 *
 * Library refresh is not poked manually here: the Compose screen model collects `getLibraryManga`
 * and `downloadCache.changes` flows, which re-emit on chapter and download writes, so the grid
 * updates naturally after each action completes.
 */
object MangaLibraryActions {

    /**
     * Resolve HTTP source URLs for sharing. Local sources are silently filtered out, matching
     * legacy `getMangaUrls`'s `as? HttpSource ?: return@mapNotNull null` guard.
     */
    fun share(mangas: List<Manga>, sourceManager: SourceManager): List<String> =
        mangas.mapNotNull { manga ->
            val source = sourceManager.get(manga.source) as? HttpSource ?: return@mapNotNull null
            source.getMangaUrl(manga)
        }

    suspend fun downloadUnread(
        mangas: List<Manga>,
        getChapter: GetChapter,
        downloadManager: DownloadManager,
    ) {
        mangas.forEach { manga ->
            val chapters = getChapter.awaitAll(manga).filter { !it.read }
            downloadManager.downloadChapters(manga, chapters)
        }
    }

    /**
     * Apply read / unread status to every chapter of every selected manga. Returns a snapshot of
     * the original chapter list per manga so [undoMarkReadStatus] can restore exact pre-update
     * state (including last_page_read), matching legacy presenter behavior.
     */
    suspend fun markReadStatus(
        mangas: List<Manga>,
        markRead: Boolean,
        getChapter: GetChapter,
        updateChapter: UpdateChapter,
    ): Map<Manga, List<Chapter>> {
        val snapshot = LinkedHashMap<Manga, List<Chapter>>()
        mangas.forEach { manga ->
            val chapters = getChapter.awaitAll(manga)
            val updates = chapters.mapNotNull { ch ->
                val id = ch.id ?: return@mapNotNull null
                ChapterUpdate(id, read = markRead, lastPageRead = 0)
            }
            updateChapter.awaitAll(updates)
            snapshot[manga] = chapters
        }
        return snapshot
    }

    suspend fun undoMarkReadStatus(
        snapshot: Map<Manga, List<Chapter>>,
        updateChapter: UpdateChapter,
    ) {
        val updates = snapshot.values.flatMap { chapters ->
            chapters.mapNotNull { ch ->
                val id = ch.id ?: return@mapNotNull null
                ChapterUpdate(id, read = ch.read, lastPageRead = ch.last_page_read.toLong())
            }
        }
        updateChapter.awaitAll(updates)
    }

    /**
     * Post-snackbar cleanup. When the user marked chapters read AND opted into
     * `removeAfterMarkedAsRead`, delete each manga's downloaded chapters from disk via its source.
     */
    fun confirmMarkReadStatus(
        snapshot: Map<Manga, List<Chapter>>,
        markRead: Boolean,
        removeAfterMarkedAsRead: Boolean,
        downloadManager: DownloadManager,
        sourceManager: SourceManager,
    ) {
        if (!markRead || !removeAfterMarkedAsRead) return
        snapshot.forEach { (manga, chapters) ->
            val source = sourceManager.get(manga.source) ?: return@forEach
            downloadManager.deleteChapters(chapters, manga, source)
        }
    }
}
