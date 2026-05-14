package eu.kanade.tachiyomi.data.track.kitsu.dto

import kotlinx.serialization.Serializable

/**
 * Flat output row produced by `KitsuApi.getUserLibrary` after the JSON:API `data` + `included`
 * graph is resolved page-by-page. The fetcher converts these into [yokai.domain.library.taste.model.TrackedEntry].
 *
 * Not annotated `@Serializable` — it's a synthesized in-process type, never on the wire.
 *
 * @property malId the MyAnimeList id resolved via Kitsu's `mappings` relationship when the
 *   manga has an `externalSite=="myanimelist/manga"` mapping. Null when no mapping exists
 *   (Kitsu-original / niche titles). Used by the cross-tracker dedup pass downstream.
 * @property anilistId the AniList id resolved via the same `mappings` mechanism (filter
 *   `externalSite=="anilist/manga"`). Catches manhwa where MAL mapping is missing but
 *   AniList mapping exists. Null when neither cross-ref is in Kitsu's mappings table.
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

// --- Wire types (JSON:API spec) ---

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
    /**
     * Kitsu uses **typed** relationships on library-entries (`manga` / `anime` / `drama`),
     * not a JSON:API-style polymorphic `media`. When `include=manga` is requested, this is
     * populated with `data = { id, type: "manga" }` for manga entries and is null (or has a
     * null `data`) for anime entries — the resolver's mapNotNull drops them either way.
     */
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
    /** Present on `type=="manga"` records. */
    val canonicalTitle: String? = null,
    /** Present on `type=="categories"` records — the category's display name. */
    val title: String? = null,
    /**
     * Present on `type=="mappings"` records — the slug for the external site this mapping
     * points at (`"myanimelist/manga"`, `"anilist/manga"`, `"anidb"`, etc.).
     */
    val externalSite: String? = null,
    /** Present on `type=="mappings"` records — the external id as a string (parse to Long). */
    val externalId: String? = null,
)

@Serializable
data class KitsuLibraryIncludedRelationships(
    val categories: KitsuRelationshipRefList? = null,
    /** Manga records carry a mappings relationship pointing at cross-site id records. */
    val mappings: KitsuRelationshipRefList? = null,
)

@Serializable
data class KitsuRelationshipRefList(
    val data: List<KitsuRelationshipId> = emptyList(),
)
