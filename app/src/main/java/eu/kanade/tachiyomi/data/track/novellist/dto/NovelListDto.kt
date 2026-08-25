package eu.kanade.tachiyomi.data.track.novellist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shapes from the service's own OpenAPI document at `/api/openapi.json`, not from the sample
 * responses: the schema marks `chapter_count` and the three collections nullable even though a live
 * search never returned a null in 90 records, and a wrong non-null here throws at parse time.
 */
@Serializable
data class NLNovel(
    val id: String,
    val slug: String,
    @SerialName("raw_title") val rawTitle: String? = null,
    @SerialName("english_title") val englishTitle: String? = null,
    @SerialName("alternate_titles") val alternateTitles: List<String>? = null,
    val description: String? = null,
    val status: String? = null,
    @SerialName("cover_image_link") val coverImageLink: String? = null,
    @SerialName("chapter_count") val chapterCount: Long? = null,
    // Only `GET /novels/{id}` carries these; the filter response omits author entirely.
    val author: NLAuthor? = null,
    val labels: List<NLLabel>? = null,
)

@Serializable
data class NLAuthor(
    val name: String,
)

@Serializable
data class NLLabel(
    val name: String,
    val type: String,
)

@Serializable
data class NLUser(
    val username: String,
)

/**
 * Request for `POST /novels/filter`. Every field is optional to the server, but the title query and
 * paging are sent explicitly so a default change on their side cannot silently alter our results.
 */
@Serializable
data class NLFilterRequest(
    @SerialName("title_search_query") val titleSearchQuery: String,
    val page: Int = 1,
    @SerialName("sort_order") val sortOrder: String = "MOST_TRENDING",
    val language: String = "UNKNOWN",
    @SerialName("label_ids") val labelIds: List<Long> = emptyList(),
    @SerialName("excluded_label_ids") val excludedLabelIds: List<Long> = emptyList(),
)

/**
 * The user's own list entry, from `GET /users/current/reading-list/{id}`.
 *
 * Unlike RanobeDB, this reads back, so an update can carry the fields it is not changing instead of
 * blanking them. [note] is the one a careless write would destroy, since nothing in this app edits it.
 */
@Serializable
data class NLReadingListEntry(
    val status: String,
    @SerialName("chapter_count") val chapterCount: Long = 0,
    val rating: Double? = null,
    val note: String? = null,
)

/**
 * Write payload for `PUT /users/current/reading-list/{id}`. [chapterCount] is not optional even
 * though the schema says it is: the route resets progress to 0 for any body that leaves it out, so a
 * status or score edit alone silently wipes the user's chapter count. Measured against the live
 * service; nothing in their document hints at it. The other fields are genuinely preserved when
 * omitted, and a null stays out of the body, so they mean "leave alone". Rating is constrained to
 * 1..10, so an unset score is omitted rather than sent as 0.
 */
@Serializable
data class NLUpdateRequest(
    @SerialName("chapter_count") val chapterCount: Long,
    val status: String? = null,
    val rating: Double? = null,
    val note: String? = null,
)
