package yokai.domain.library.taste.model

/**
 * A single tracked manga, normalized across trackers, used to build a [TasteProfile].
 *
 * @property trackerId stable tracker id (matches `manga_sync.sync_id` / [eu.kanade.tachiyomi.data.track.TrackService.id]).
 * @property remoteId tracker's media id.
 * @property malId cross-tracker identity (MyAnimeList id) when known — MAL entries always
 *   populate this with their own remote id, AniList entries populate it from `Media.idMal`,
 *   Kitsu via its `mappings` records (filter `externalSite=="myanimelist/manga"`). `null`
 *   means no MAL cross-ref available for this entry.
 * @property anilistId cross-tracker identity (AniList id) when known — AniList entries
 *   trivially populate it with their own remote id, Kitsu via its `mappings` records
 *   (filter `externalSite=="anilist/manga"`). `null` means no AniList cross-ref available.
 *   Second cross-ref key catches the "AniList has no idMal + Kitsu has no MAL mapping" gap
 *   that the malId-only dedup couldn't close.
 * @property score normalized to 0..1; `-1.0` when unrated. The compute formula treats
 *   unrated entries as still contributing via [status] weight but with no rating term.
 * @property tags lowercased + trimmed, deduplicated within the entry.
 * @property fetchedAt epoch millis the entry was fetched (Layer B). `null` for Layer A
 *   (live JOIN — no fetch happened).
 */
data class TrackedEntry(
    val trackerId: Long,
    val remoteId: Long,
    val title: String,
    val score: Double,
    val status: TrackStatus,
    val tags: List<String>,
    val fetchedAt: Long?,
    val malId: Long? = null,
    val anilistId: Long? = null,
)
