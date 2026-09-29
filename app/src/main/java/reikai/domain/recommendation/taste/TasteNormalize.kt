package reikai.domain.recommendation.taste

/**
 * Normalize a raw tracker rating on a 0..[max] scale to 0..1. A raw 0 or null (unrated) becomes -1.0
 * so the taste compute can tell "rated low" from "no rating". Shared by every [TrackerLibraryFetcher]
 * (AniList /100, Kitsu /20, MAL / Shikimori / Bangumi /10).
 */
fun normalizeTrackerScore(raw: Int?, max: Int): Double =
    if (raw == null || raw <= 0) -1.0 else (raw.toDouble() / max).coerceIn(0.0, 1.0)

/**
 * Canonical genre/tag keys (lowercased, trimmed, blanks dropped, each once) used as
 * [TasteProfile.tagScores] keys. The fetcher write-path and the ranker read-path both normalize
 * through this, so a candidate's tags match the profile keys and a repeated genre counts once on
 * both sides.
 */
fun Iterable<String>.toTagKeys(): List<String> = map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct()
