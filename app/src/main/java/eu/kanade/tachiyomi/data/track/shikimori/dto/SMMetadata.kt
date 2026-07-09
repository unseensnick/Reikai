package eu.kanade.tachiyomi.data.track.shikimori.dto

import kotlinx.serialization.Serializable

// RK: "Fill from tracker" metadata (ported from Komikku, re-typed to Reikai's poster{mainUrl} shape,
// plus genres). Reuses SMPersonRole / SMPoster from SMManga.kt. Manga id is a GraphQL ID (string).
@Serializable
data class SMMetadata(
    val data: SMMetadataData = SMMetadataData(),
)

@Serializable
data class SMMetadataData(
    val mangas: List<SMMetadataResult> = emptyList(),
)

@Serializable
data class SMMetadataResult(
    val id: String,
    val name: String,
    val description: String? = null,
    val poster: SMPoster? = null,
    val personRoles: List<SMPersonRole> = emptyList(),
    val genres: List<SMMetadataGenre> = emptyList(),
)

@Serializable
data class SMMetadataGenre(
    val name: String,
)
