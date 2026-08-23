package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import reikai.domain.recommendation.ReikaiRecommendationPreferences

/**
 * Pulls the user's full Kitsu manga library through GraphQL (`currentProfile.library.all`, paged 500
 * an entry) and normalizes each entry into a [TrackedEntry].
 *
 * Score is Kitsu's native 1..20 rating regardless of display preference, so it divides by 20 and
 * missing or 0 becomes -1.0. Statuses are Kitsu's own tokens, not the other trackers'.
 */
class KitsuLibraryFetcher(
    private val kitsu: Kitsu,
    private val preferences: ReikaiRecommendationPreferences,
) : TrackerLibraryFetcher {

    override val trackerId: Long = kitsu.id

    override fun isPullRequested(): Boolean = preferences.pullLibraryFromKitsu.get()

    override fun isEnabled(): Boolean = isPullRequested() && kitsu.isLoggedIn

    override suspend fun fetchLibrary(): List<TrackedEntry> =
        kitsu.getUserLibrary().map { it.toTrackedEntry() }

    private fun KitsuLibraryEntry.toTrackedEntry(): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = mangaId,
        title = title,
        score = normalizeTrackerScore(ratingTwenty, 20),
        status = mapStatus(status),
        tags = tags.map { it.toTagKey() }.filter { it.isNotEmpty() }.distinct(),
        malId = malId,
        anilistId = anilistId,
        adult = kitsuAdultContent(nsfwCategories, sfw),
    )

    // Lower-cased because GraphQL reports the status enum in upper case where the JSON:API reported
    // it lower, and both spellings mean the same thing.
    private fun mapStatus(raw: String): TrackStatus = when (raw.lowercase()) {
        "current" -> TrackStatus.READING
        "completed" -> TrackStatus.COMPLETED
        "on_hold" -> TrackStatus.ON_HOLD
        "dropped" -> TrackStatus.DROPPED
        "planned" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.UNKNOWN
    }
}

/**
 * Kitsu's two adult signals, neither of which can certify a title clean, so this never answers
 * [AdultContent.CLEAN]: `sfw` only means "not rated R18" and an entry with no flagged category only
 * means nobody tagged one. Leaving the rest [AdultContent.UNKNOWN] keeps the keyword fallback and
 * the user's own tag picks able to speak. `sfw == false` is exactly `ageRating == R18`, so it never
 * fires on violence: Berserk is rated R and reads as safe. Which categories count is
 * [isKitsuSexualCategory].
 */
internal fun kitsuAdultContent(nsfwCategories: List<String>, sfw: Boolean?): AdultContent {
    val sexualCategory = nsfwCategories.any { isKitsuSexualCategory(it) }
    return if (sexualCategory || sfw == false) AdultContent.ADULT else AdultContent.UNKNOWN
}

/**
 * Whether a category Kitsu flags NSFW is sexual content by Reikai's definition, given the tags the
 * user has said are not. Kitsu's own flag is the wider of the two, so reading it raw would both
 * classify entries this app would not and strip genres from Fill-from-tracker that every other
 * tracker keeps. `KitsuApi.getMangaMetadata` and [kitsuAdultContent] share it so one answer serves
 * both; only the metadata path passes [allowedTags], since the library mapping is resolved against
 * the user's picks later, at read time.
 */
internal fun isKitsuSexualCategory(category: String, allowedTags: Set<String> = emptySet()): Boolean {
    val key = category.toTagKey()
    return key !in NON_SEXUAL_NSFW_CATEGORIES && key !in allowedTags
}

/**
 * Flagged NSFW by Kitsu but pinned as not sexual content by AdultContentTest: Nudity carries
 * `isAdult: false` on AniList, and Yuri is orientation rather than explicitness. Both still arrive
 * as ordinary tags, where the user can deny them if they disagree.
 */
private val NON_SEXUAL_NSFW_CATEGORIES = setOf("nudity", "yuri")
