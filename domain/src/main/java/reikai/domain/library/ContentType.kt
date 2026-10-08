package reikai.domain.library

/**
 * The content-type filter shared by Browse and Library. Backs the
 * sticky `All / Manga / Novels` chip that replaces the Yōkai-era Browse Manga|LN sub-tab toggle.
 * Persisted via [tachiyomi.core.common.preference.getEnum], so the constant names are load-bearing.
 */
enum class ContentType {
    ALL,
    MANGA,
    NOVELS,
}

/** Whether this chip shows entries of [type]: its own type, or every type under [ContentType.ALL]. */
fun ContentType.includes(type: ContentType): Boolean = this == ContentType.ALL || this == type

/** Whether a list holding [types] mixes content types, the one case where a row names its own type. */
fun mixesContentTypes(types: Iterable<ContentType>): Boolean = types.distinct().size > 1

/** The one type [types] all share, or null when empty or mixed: a per-type action needs exactly one. */
fun singleContentType(types: Iterable<ContentType>): ContentType? = types.distinct().singleOrNull()
