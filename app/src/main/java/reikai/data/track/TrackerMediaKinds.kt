package reikai.data.track

/**
 * Which of a tracker's titles are light novels, for every search path and the recommendations' title
 * lookup alike. MyAnimeList's API says light_novel / novel, Jikan's mirror of it Light Novel / Novel.
 */
fun isMyAnimeListNovel(mediaType: String?): Boolean = mediaType?.contains("novel", ignoreCase = true) == true

/** The MangaUpdates series types that are not manga; upstream's manga search excludes them server-side. */
val MANGA_UPDATES_NON_MANGA_TYPES = listOf("drama cd", "novel")

fun isMangaUpdatesNovel(type: String?): Boolean = type.equals("novel", ignoreCase = true)

fun isMangaUpdatesManga(type: String?): Boolean =
    MANGA_UPDATES_NON_MANGA_TYPES.none { it.equals(type, ignoreCase = true) }

/** AniList files a light novel as type MANGA with format NOVEL; its GraphQL searches filter on the same value. */
fun isAnilistNovel(format: String?): Boolean = format == "NOVEL"
