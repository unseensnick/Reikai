package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import tachiyomi.domain.manga.model.Manga

/**
 * A user's per-field overrides for a novel, stored non-destructively in `custom_novel_info` (the source
 * `novels` row is never changed). A null field means "no override, use the source value". The novel twin
 * of [tachiyomi.domain.manga.model.CustomMangaInfo].
 */
@Immutable
data class CustomNovelInfo(
    val novelId: Long,
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
) {
    /** No overrides set: saving this clears the row (Reset to source). */
    val isEmpty: Boolean
        get() = title == null && author == null && artist == null && description == null &&
            genre == null && status == null && thumbnailUrl == null
}

/**
 * Non-destructive display overlay: each set custom field wins over the source value, the rest pass
 * through. Apply to a display-only copy in the ScreenModel / list mappers; the source [Novel] row stays
 * untouched.
 *
 * The [Manga] overload exists because the library renders a novel as a manga-shaped row, and applies the
 * overlay at the display read so filter, sort, search and grouping keep reading the source values.
 */
fun Manga.withCustomInfo(custom: CustomNovelInfo?): Manga {
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

fun Novel.withCustomInfo(custom: CustomNovelInfo?): Novel {
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
