package eu.kanade.tachiyomi.data.track.kitsu.dto

import kotlinx.serialization.Serializable

/**
 * RK: flat row produced by `KitsuApi.getUserLibrary` after the JSON:API `data` + `included` graph
 * is resolved page-by-page. The recommendation taste fetcher converts these into TrackedEntry.
 * Not `@Serializable`: synthesized in process, never on the wire. Cross-tracker keys ([malId] /
 * [anilistId]) come from the manga's `mappings` relationship.
 */
data class KitsuLibraryEntry(
    val mangaId: Long,
    val title: String,
    val status: String,
    val ratingTwenty: Int?,
    val tags: List<String>,
    val malId: Long?,
    val anilistId: Long?,
)

// --- Wire types (JSON:API). Ids are Long to match Kitsu's existing Mihon DTOs. ---

@Serializable
data class KitsuLibraryResult(
    val data: List<KitsuLibraryRow> = emptyList(),
    val included: List<KitsuLibraryIncluded> = emptyList(),
    val links: KitsuLibraryLinks = KitsuLibraryLinks(),
)

@Serializable
data class KitsuLibraryLinks(
    val next: String? = null,
)

@Serializable
data class KitsuLibraryRow(
    val id: Long,
    val attributes: KitsuLibraryRowAttributes,
    val relationships: KitsuLibraryRowRelationships = KitsuLibraryRowRelationships(),
)

@Serializable
data class KitsuLibraryRowAttributes(
    val status: String,
    val ratingTwenty: Int? = null,
)

@Serializable
data class KitsuLibraryRowRelationships(
    // Kitsu uses typed relationships (manga / anime / drama), not a polymorphic media. Anime entries
    // leave this null and the resolver drops them.
    val manga: KitsuRelationshipRef? = null,
)

@Serializable
data class KitsuRelationshipRef(
    val data: KitsuRelationshipId? = null,
)

@Serializable
data class KitsuRelationshipId(
    val id: Long,
    val type: String,
)

@Serializable
data class KitsuLibraryIncluded(
    val id: Long,
    val type: String,
    val attributes: KitsuLibraryIncludedAttributes = KitsuLibraryIncludedAttributes(),
    val relationships: KitsuLibraryIncludedRelationships? = null,
)

@Serializable
data class KitsuLibraryIncludedAttributes(
    /** Present on `type=="manga"`. */
    val canonicalTitle: String? = null,
    /** Present on `type=="categories"`: the category's display name. */
    val title: String? = null,
    /** Present on `type=="mappings"`: the external site slug (`"myanimelist/manga"`, etc.). */
    val externalSite: String? = null,
    /** Present on `type=="mappings"`: the external id as a string. */
    val externalId: String? = null,
)

@Serializable
data class KitsuLibraryIncludedRelationships(
    val categories: KitsuRelationshipRefList? = null,
    val mappings: KitsuRelationshipRefList? = null,
)

@Serializable
data class KitsuRelationshipRefList(
    val data: List<KitsuRelationshipId> = emptyList(),
)
