package eu.kanade.tachiyomi.data.track.bangumi.dto

import kotlinx.serialization.Serializable

@Serializable
// Incomplete DTO with only the attribute we need from /v0/me.
data class BGMUser(
    val username: String,
)
