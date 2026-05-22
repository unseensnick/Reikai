package eu.kanade.tachiyomi.data.track.kitsu

/**
 * Discriminator for Kitsu searches. Kitsu stores manga and novels in the same Algolia index and
 * distinguishes via the `subtype` field (`manga`, `manhwa`, `manhua`, `doujin`, `novel`, `oel`).
 * The MANGA path keeps the existing "everything except novel" filter; the NOVEL path inverts it.
 */
enum class KitsuMediaType { MANGA, NOVEL }
