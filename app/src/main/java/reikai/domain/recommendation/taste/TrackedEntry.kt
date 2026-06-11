package reikai.domain.recommendation.taste

/**
 * A single tracked manga, normalized across trackers, used to build a [TasteProfile].
 *
 * @property trackerId stable tracker id (matches `manga_sync.sync_id` / the tracker's `id`).
 * @property remoteId tracker's media id.
 * @property score normalized to 0..1; `-1.0` when unrated. The compute formula treats unrated
 *   entries as still contributing via [status] weight but with no rating term.
 * @property tags lowercased + trimmed, deduplicated within the entry.
 * @property malId cross-tracker identity (MyAnimeList id) when known, for de-duplicating the same
 *   series tracked on more than one service. `null` when no MAL cross-ref is available.
 * @property anilistId cross-tracker identity (AniList id) when known. `null` when unavailable.
 *   Catches the "AniList has no idMal and the entry has no MAL mapping" gap the malId-only dedup
 *   can't close.
 */
data class TrackedEntry(
    val trackerId: Long,
    val remoteId: Long,
    val title: String,
    val score: Double,
    val status: TrackStatus,
    val tags: List<String>,
    val malId: Long? = null,
    val anilistId: Long? = null,
)
