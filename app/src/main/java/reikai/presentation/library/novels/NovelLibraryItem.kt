package reikai.presentation.library.novels

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.LibraryNovel
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * Shapes a [LibraryNovel] into the library's manga-shaped [LibraryItem] so the existing library
 * views, grids, hopper, badges, and selection render novels with no changes. The synthetic [Manga]
 * carries the novel's own real id; identity across content types comes from [EntryId], never from
 * the raw id, since a manga and a novel may share one. `source` is left at the factory default
 * since novels use a String source id (the language badge is fed [sourceLanguage] resolved by the
 * screen model instead).
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
        id = n.id,
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
        // The neutral identity every shared decision site keys on; the manga-shaped row above is a
        // rendering convenience, not an identity.
        entryId = EntryId.Novel(n.id),
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
