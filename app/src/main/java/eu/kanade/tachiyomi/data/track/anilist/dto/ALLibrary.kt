package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for `MediaListCollection` — AniList's "give me every entry in this user's manga
 * library" query. Returned in one round trip; used by the taste-profile library fetcher.
 *
 * Shape: data → MediaListCollection → lists (one per status group / custom list) → entries.
 * An entry's `media` carries the genres + tag names we feed into the taste profile.
 */
@Serializable
data class ALUserLibraryResult(
    val data: ALUserLibraryData,
)

@Serializable
data class ALUserLibraryData(
    @SerialName("MediaListCollection")
    val mediaListCollection: ALMediaListCollection,
)

@Serializable
data class ALMediaListCollection(
    val lists: List<ALMediaListGroup>,
)

@Serializable
data class ALMediaListGroup(
    val entries: List<ALLibraryEntry>,
)

@Serializable
data class ALLibraryEntry(
    val status: String,
    val scoreRaw: Int,
    val media: ALLibraryMedia,
)

@Serializable
data class ALLibraryMedia(
    val id: Long,
    /** AniList's cross-ref to the MyAnimeList id; null when AniList has no MAL mapping. */
    val idMal: Long? = null,
    val title: ALLibraryMediaTitle,
    val genres: List<String> = emptyList(),
    val tags: List<ALLibraryTag> = emptyList(),
)

@Serializable
data class ALLibraryMediaTitle(
    val userPreferred: String,
)

@Serializable
data class ALLibraryTag(
    val name: String,
)
