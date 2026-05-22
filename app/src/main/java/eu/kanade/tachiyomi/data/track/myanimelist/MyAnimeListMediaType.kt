package eu.kanade.tachiyomi.data.track.myanimelist

/**
 * Discriminator for MAL searches that need to scope to either manga or light novels. MAL stores
 * both under the same `/manga` endpoint and discriminates via the `media_type` / `publishing_type`
 * field (values like `manga`, `manhwa`, `novel`, `light_novel`). The two branches differ only in
 * how the result set is post-filtered.
 */
enum class MyAnimeListMediaType { MANGA, NOVEL }
