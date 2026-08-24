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
)
