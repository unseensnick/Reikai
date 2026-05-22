package eu.kanade.tachiyomi.data.track.anilist

/**
 * Discriminator for AniList GraphQL queries that need to scope to either manga or light novels.
 * AniList itself has no top-level NOVEL type; light novels are stored as MANGA with
 * format = NOVEL. Both branches still pass type: MANGA; the format filter is what splits them.
 */
enum class AnilistMediaType { MANGA, NOVEL }
