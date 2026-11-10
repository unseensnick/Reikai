package reikai.domain.recommendation.taste

import reikai.domain.library.tagKey

/**
 * Normalize a raw tracker rating on a 0..[max] scale to 0..1. A raw 0 or null (unrated) becomes -1.0
 * so the taste compute can tell "rated low" from "no rating". Shared by every [TrackerLibraryFetcher]
 * (AniList /100, Kitsu /20, MAL / Shikimori / Bangumi /10).
 */
fun normalizeTrackerScore(raw: Int?, max: Int): Double =
    if (raw == null || raw <= 0) -1.0 else (raw.toDouble() / max).coerceIn(0.0, 1.0)

/**
 * Canonical genre/tag keys used as [TasteProfile.tagScores] keys, folded by [tagKey], the library's one tag
 * rule, so "Slice-of-Life" and "slice of life" are one key here as in the tag filter. The fetcher write-path
 * and the ranker read-path both normalize through this, and a repeated genre counts once on both sides.
 */
fun Iterable<String>.toTagKeys(): List<String> = map(::tagKey).filter { it.isNotEmpty() }.distinct()
