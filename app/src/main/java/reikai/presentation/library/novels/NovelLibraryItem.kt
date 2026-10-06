package reikai.presentation.library.novels

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.LibraryNovel
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.SourceBadge
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
    badgePrefs: LibraryBadgePrefs,
    sourceLanguage: String,
    sourceIcon: SourceBadge,
    sourceName: String,
): LibraryItem {
    val n = novel
    val synthetic = Manga.create().copy(
        id = n.id,
        url = n.url,
        title = n.title,
        artist = n.artist,
        author = n.author,
        description = n.description,
        genre = n.genre,
        status = n.status,
        thumbnailUrl = n.thumbnailUrl,
        favoriteAt = n.favoriteAt ?: 0L,
        lastUpdate = n.lastUpdate,
        coverLastModified = n.coverLastModified,
        initialized = n.initialized,
        chapterFlags = n.chapterFlags,
        updateStrategy = n.updateStrategy,
        // Carried so the shared query kernel and filter answer `notes:`, `nextupdate:`, `fetchinterval:`
        // and the custom-interval filter on a novel exactly as on a manga.
        notes = n.notes,
        nextUpdate = n.nextUpdate,
        fetchInterval = n.fetchInterval,
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
        // The shared query kernel reads these off the row, so they are populated here rather than
        // resolved again at filter time. Lowercased to match how the manga side supplies its own.
        sourceName = sourceName.lowercase(),
        sourceLanguage = sourceLanguage,
        badges = badgePrefs.badges(
            downloadCount = downloadCount.toInt(),
            unreadCount = unreadCount,
            isLocal = false,
            sourceLanguage = sourceLanguage,
            sourceBadge = sourceIcon,
            // Always carried: the cover is fetched with it, and it is not a visible badge.
            coverSourceId = n.source,
        ),
    )
}
