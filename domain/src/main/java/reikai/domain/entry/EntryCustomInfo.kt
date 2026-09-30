package reikai.domain.entry

import reikai.domain.novel.model.NovelHistoryWithRelations
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedNovel
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * A user's display-only overrides for one entry of either content type. A null field means "no
 * override, use the source value"; the source row itself is never changed.
 */
interface EntryCustomInfo {
    val title: String?
    val author: String?
    val artist: String?
    val description: String?
    val genre: List<String>?
    val status: Long?
    val thumbnailUrl: String?

    /** No overrides set: saving this clears the row (Reset to source). */
    val isEmpty: Boolean
        get() = title == null && author == null && artist == null && description == null &&
            genre == null && status == null && thumbnailUrl == null
}

/**
 * Each set field wins, the rest pass through. Novels reach this too, since the library renders a novel
 * as a manga-shaped row; the overlay is applied at the display read so filter, sort, search and grouping
 * keep reading the source values.
 */
fun Manga.withCustomInfo(custom: EntryCustomInfo?): Manga {
    if (custom == null) return this
    return copy(
        title = custom.title ?: title,
        author = custom.author ?: author,
        artist = custom.artist ?: artist,
        description = custom.description ?: description,
        genre = custom.genre ?: genre,
        status = custom.status ?: status,
        thumbnailUrl = custom.thumbnailUrl ?: thumbnailUrl,
    )
}

/**
 * Lays each row's override over it, keyed by the entry's real id. Every feed runs this after its filters
 * and download lookups, which read the stored title a download folder is named from.
 */
inline fun <T> List<T>.overlayCustomInfo(
    byId: Map<Long, EntryCustomInfo>,
    id: (T) -> Long,
    apply: T.(EntryCustomInfo?) -> T,
): List<T> = if (byId.isEmpty()) this else map { it.apply(byId[id(it)]) }

// The feed rows draw only a title and a cover, so those are the only fields they take.

fun UpdatesWithRelations.withCustomInfo(custom: EntryCustomInfo?): UpdatesWithRelations {
    if (custom == null) return this
    return copy(
        mangaTitle = custom.title ?: mangaTitle,
        coverData = coverData.copy(url = custom.thumbnailUrl ?: coverData.url),
    )
}

fun NovelUpdateWithRelations.withCustomInfo(custom: EntryCustomInfo?): NovelUpdateWithRelations {
    if (custom == null) return this
    return copy(
        novelTitle = custom.title ?: novelTitle,
        coverData = coverData.copy(url = custom.thumbnailUrl ?: coverData.url),
    )
}

fun HistoryWithRelations.withCustomInfo(custom: EntryCustomInfo?): HistoryWithRelations {
    if (custom == null) return this
    return copy(
        title = custom.title ?: title,
        coverData = coverData.copy(url = custom.thumbnailUrl ?: coverData.url),
    )
}

fun NovelHistoryWithRelations.withCustomInfo(custom: EntryCustomInfo?): NovelHistoryWithRelations {
    if (custom == null) return this
    return copy(
        title = custom.title ?: title,
        coverData = coverData.copy(url = custom.thumbnailUrl ?: coverData.url),
    )
}

fun RecentlyAddedManga.withCustomInfo(custom: EntryCustomInfo?): RecentlyAddedManga {
    if (custom == null) return this
    return copy(
        title = custom.title ?: title,
        coverData = coverData.copy(url = custom.thumbnailUrl ?: coverData.url),
    )
}

fun RecentlyAddedNovel.withCustomInfo(custom: EntryCustomInfo?): RecentlyAddedNovel {
    if (custom == null) return this
    return copy(
        title = custom.title ?: title,
        coverData = coverData.copy(url = custom.thumbnailUrl ?: coverData.url),
    )
}
