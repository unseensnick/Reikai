package eu.kanade.tachiyomi.data.track.ranobedb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RanobeDB wraps single-item responses in their type name but returns list responses flat, so
 * `/series/{id}` yields `{"series": {...}}` while `/series?q=` yields `{"series": [...], ...}`.
 */
@Serializable
data class RDBSeriesList(
    val series: List<RDBSeries> = emptyList(),
)

@Serializable
data class RDBSeriesOne(
    val series: RDBSeries,
)

@Serializable
data class RDBSeries(
    val id: Long,
    val title: String,
    val romaji: String? = null,
    @SerialName("title_orig") val titleOrig: String? = null,
    @SerialName("romaji_orig") val romajiOrig: String? = null,
    val hidden: Boolean = false,
    val description: String? = null,
    @SerialName("publication_status") val publicationStatus: String? = null,
    // Volume count. Prefer this over the sibling `volumes.count`, which is nullable and typed
    // string | number | bigint because it reaches the wire as an unnormalised Postgres count.
    @SerialName("c_num_books") val volumeCount: Long = 0,
    // Cover source differs by endpoint: the list gives one representative `book`, the detail
    // gives the whole `books` array and no top-level image.
    val book: RDBSeriesBook? = null,
    val books: List<RDBBook> = emptyList(),
    val staff: List<RDBStaff> = emptyList(),
    val tags: List<RDBTag> = emptyList(),
)

@Serializable
data class RDBSeriesBook(
    val image: RDBImage? = null,
)

@Serializable
data class RDBBook(
    @SerialName("sort_order") val sortOrder: Int = 0,
    val image: RDBImage? = null,
)

@Serializable
data class RDBImage(
    val filename: String,
)

@Serializable
data class RDBStaff(
    val name: String,
    @SerialName("role_type") val roleType: String,
)

@Serializable
data class RDBTag(
    val name: String,
    val ttype: String,
)

@Serializable
data class RDBUser(
    val username: String,
)

/**
 * Write payload for `PUT /api/v0/user/series/{id}`.
 *
 * `langs`, `formats` and `selectedCustLabels` carry no server-side default, so they must be sent
 * even when empty or the route rejects the body. Score is 1..10 on the wire; the server scales it
 * to its own 100-point storage, so never pre-multiply here.
 */
@Serializable
data class RDBSeriesListEntry(
    @SerialName("readingStatus") val readingStatus: String,
    val score: Double? = null,
    @SerialName("volumes_read") val volumesRead: Long? = null,
    val started: String? = null,
    val finished: String? = null,
    val notes: String? = null,
    // No Kotlin default on purpose: kotlinx omits any property equal to its declared default, and
    // these three carry no server-side default either, so a default here would drop them from the
    // body and the route would reject it.
    val langs: List<String>,
    val formats: List<String>,
    @SerialName("selectedCustLabels") val selectedCustLabels: List<Long>,
)
