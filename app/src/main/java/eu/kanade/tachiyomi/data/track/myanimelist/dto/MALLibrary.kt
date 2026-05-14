package eu.kanade.tachiyomi.data.track.myanimelist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for `/users/@me/mangalist?fields=list_status{status,score,is_rereading},genres` —
 * MAL v2's paginated list of the user's tracked manga. The taste-profile fetcher walks
 * `paging.next` until exhausted to collect every entry.
 *
 * Note on `is_rereading`: when true (regardless of the declared `status`), the entry is
 * mapped to [yokai.domain.library.taste.model.TrackStatus.READING] at the fetcher boundary —
 * mirrors AniList's `REPEATING → READING` rule so an actively-rereading entry contributes
 * the same engaged-reading signal across trackers.
 */
@Serializable
data class MALLibraryResult(
    val data: List<MALLibraryItem>,
    val paging: MALLibraryPaging,
)

@Serializable
data class MALLibraryPaging(
    val next: String? = null,
)

@Serializable
data class MALLibraryItem(
    val node: MALLibraryNode,
    @SerialName("list_status")
    val listStatus: MALLibraryListStatus,
)

@Serializable
data class MALLibraryNode(
    val id: Long,
    val title: String,
    val genres: List<MALLibraryGenre> = emptyList(),
)

@Serializable
data class MALLibraryGenre(
    val name: String,
)

@Serializable
data class MALLibraryListStatus(
    val status: String,
    val score: Int,
    @SerialName("is_rereading")
    val isRereading: Boolean = false,
)
