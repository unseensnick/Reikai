package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryCustomInfo

/**
 * A user's per-field overrides for a novel, stored non-destructively in `custom_novel_info` (the source
 * `novels` row is never changed). The overlay rules live on [EntryCustomInfo].
 */
@Immutable
data class CustomNovelInfo(
    val novelId: Long,
    override val title: String? = null,
    override val author: String? = null,
    override val artist: String? = null,
    override val description: String? = null,
    override val genre: List<String>? = null,
    override val status: Long? = null,
    override val thumbnailUrl: String? = null,
) : EntryCustomInfo

/**
 * Non-destructive display overlay: each set custom field wins over the source value, the rest pass
 * through. The novel model's own copy of [reikai.domain.entry.withCustomInfo], which a manga-shaped
 * library row takes instead.
 */
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
