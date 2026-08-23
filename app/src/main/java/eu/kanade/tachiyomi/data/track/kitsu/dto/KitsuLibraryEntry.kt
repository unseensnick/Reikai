package eu.kanade.tachiyomi.data.track.kitsu.dto

/**
 * RK: flat row produced by `KitsuApi.getUserLibrary` from the GraphQL library connection. The
 * recommendation taste fetcher converts these into TrackedEntry. Not `@Serializable`: synthesized
 * in process, never on the wire. Cross-tracker keys ([malId] / [anilistId]) come from the media's
 * `mappings` connection.
 */
data class KitsuLibraryEntry(
    val mangaId: Long,
    val title: String,
    val status: String,
    val ratingTwenty: Int?,
    val tags: List<String>,
    val malId: Long?,
    val anilistId: Long?,
    /** Titles of this entry's categories that Kitsu flags NSFW, kept separate from [tags] so the
     *  fetcher can decide; two of the flagged ones are not sexual content by Reikai's definition. */
    val nsfwCategories: List<String> = emptyList(),
    /** Kitsu's `sfw`, which is `ageRating != R18`. Null when the query did not ask. */
    val sfw: Boolean? = null,
)
