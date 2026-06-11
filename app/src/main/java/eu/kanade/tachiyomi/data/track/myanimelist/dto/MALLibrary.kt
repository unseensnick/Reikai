package eu.kanade.tachiyomi.data.track.myanimelist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RK: wire types for the `/users/@me/mangalist` pull used by the recommendation taste profile.
 * Requested with `fields=list_status,genres` so genres come inline on every page (never per title).
 * Reuses [MALSearchPaging] for the cursor.
 */
@Serializable
data class MALLibraryResult(
    val data: List<MALLibraryItem> = emptyList(),
    val paging: MALSearchPaging,
)

@Serializable
data class MALLibraryItem(
    val node: MALLibraryNode,
    @SerialName("list_status")
    val listStatus: MALLibraryListStatus? = null,
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
    val status: String? = null,
    val score: Int = 0,
    @SerialName("is_rereading")
    val isRereading: Boolean = false,
)
