package reikai.domain.recommendation.taste

/**
 * A single tracked manga, normalized across trackers, used to build a [TasteProfile].
 *
 * @property score normalized to 0..1, or `-1.0` when unrated, which still contributes through the
 *   [status] weight but with no rating term.
 * @property tags lowercased, trimmed and deduplicated within the entry.
 * @property malId cross-tracker identity for deduplicating one series tracked on several services.
 * @property anilistId the second cross-tracker identity, which closes the gap where AniList reports
 *   no idMal and the entry has no MAL mapping.
 * @property adult what the tracker says about sexual content, defaulting to
 *   [AdultContent.UNKNOWN] for a tracker whose pull does not ask.
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
    val adult: AdultContent = AdultContent.UNKNOWN,
)
