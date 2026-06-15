package reikai.presentation.library.novels

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.novel.model.LibraryNovel
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * Disguises a [LibraryNovel] as the library's manga-shaped [LibraryItem] so the existing library
 * views, grids, hopper, badges, and selection render novels with no changes (P5 S6). The synthetic
 * [Manga] carries a **negative id** (`-novel.id`): manga ids are positive, so novel and manga
 * selection never collide and tap routing is a one-line `if (id < 0)`. `source` is left at the
 * factory default since novels use a String source id (the language badge is fed [sourceLanguage]
 * resolved by the screen model instead).
 */
fun LibraryNovel.toLibraryItem(
    downloadBadge: Boolean,
    unreadBadge: Boolean,
    languageBadge: Boolean,
    sourceLanguage: String,
    sourceBadge: Boolean,
    sourceSite: String?,
    sourceIconUrl: String?,
): LibraryItem {
    val n = novel
    val synthetic = Manga.create().copy(
        id = -n.id,
        favorite = true,
        url = n.url,
        title = n.title,
        artist = n.artist,
        author = n.author,
        description = n.description,
        genre = n.genre,
        status = n.status,
        thumbnailUrl = n.thumbnailUrl,
        dateAdded = n.dateAdded,
        lastUpdate = n.lastUpdate,
        coverLastModified = n.coverLastModified,
        initialized = n.initialized,
        chapterFlags = n.chapterFlags,
        updateStrategy = n.updateStrategy,
    )
    val libraryManga = LibraryManga(
        manga = synthetic,
        categories = categories,
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = bookmarkCount,
        latestUpload = latestUpload,
        chapterFetchedAt = chapterFetchedAt,
        lastRead = lastRead,
    )
    return LibraryItem(
        libraryManga = libraryManga,
        downloadCount = downloadCount.toInt(),
        unreadCount = unreadCount,
        isLocal = false,
        badges = LibraryItem.Badges(
            downloadCount = if (downloadBadge) downloadCount.toInt() else 0,
            unreadCount = if (unreadBadge) unreadCount else 0,
            isLocal = false,
            sourceLanguage = if (languageBadge) sourceLanguage else "",
            // The cover Referer is always carried (it isn't a visible badge); the source icon honors
            // the source-badge display toggle, mirroring how the manga side gates `source`.
            coverSite = sourceSite,
            sourceIconUrl = if (sourceBadge) sourceIconUrl else null,
        ),
    )
}
