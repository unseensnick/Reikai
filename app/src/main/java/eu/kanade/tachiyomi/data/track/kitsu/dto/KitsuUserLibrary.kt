package eu.kanade.tachiyomi.data.track.kitsu.dto

import kotlinx.serialization.Serializable

// RK: GraphQL shape of the user's whole manga library, for the recommendation taste profile.
// Upstream has no whole-library query, so none of this has a counterpart to sync against.

@Serializable
data class KitsuUserLibraryResult(
    val data: KitsuUserLibraryData,
)

@Serializable
data class KitsuUserLibraryData(
    val currentProfile: KitsuUserLibraryProfile? = null,
)

@Serializable
data class KitsuUserLibraryProfile(
    val library: KitsuUserLibrary,
)

@Serializable
data class KitsuUserLibrary(
    val all: KitsuUserLibraryConnection,
)

@Serializable
data class KitsuUserLibraryConnection(
    val pageInfo: KitsuPageInfo,
    val nodes: List<KitsuUserLibraryNode> = emptyList(),
)

@Serializable
data class KitsuPageInfo(
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@Serializable
data class KitsuUserLibraryNode(
    val status: String,
    /** Kitsu's native 2..20 scale, the same one `ratingTwenty` carried on the JSON:API. */
    val rating: Int? = null,
    val media: KitsuUserLibraryMedia? = null,
)

@Serializable
data class KitsuUserLibraryMedia(
    val id: String,
    /** Kitsu's own safe-for-work helper, which is exactly `ageRating != R18`. Absent means the query
     *  did not ask; false is the only value that positively says adult. */
    val sfw: Boolean? = null,
    val titles: KitsuUserLibraryTitles = KitsuUserLibraryTitles(),
    val categories: KitsuCategoryConnection = KitsuCategoryConnection(),
    val mappings: KitsuMappingConnection = KitsuMappingConnection(),
)

@Serializable
data class KitsuUserLibraryTitles(
    val preferred: String? = null,
)

@Serializable
data class KitsuCategoryConnection(
    val nodes: List<KitsuCategoryNode> = emptyList(),
)

@Serializable
data class KitsuCategoryNode(
    /** A localized field resolves to a loose locale-keyed map, not a bare string. */
    val title: Map<String, String> = emptyMap(),
    /** Kitsu flags 25 of its 243 categories NSFW. The metadata query drops them from the genre list;
     *  the library query reads them to decide whether an entry is adult. */
    val isNsfw: Boolean = false,
)

@Serializable
data class KitsuMappingConnection(
    val nodes: List<KitsuMappingNode> = emptyList(),
)

@Serializable
data class KitsuMappingNode(
    /** The enum name, e.g. `MYANIMELIST_MANGA`, not the JSON:API's `myanimelist/manga` value. */
    val externalSite: String,
    val externalId: String,
)
