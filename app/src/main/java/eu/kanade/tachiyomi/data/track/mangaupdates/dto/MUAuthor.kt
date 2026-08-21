package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.Serializable

// RK: both fields are nullable where upstream declares them non-null, because the search record
// omits them and one missing name would fail deserialization for the whole response.
@Serializable
data class MUAuthor(
    val type: String? = null,
    val name: String? = null,
)

// RK: the author/artist split lives here so the search results and Fill from tracker cannot answer
// it differently. Matched as a substring, not for equality as upstream does, because MangaUpdates
// qualifies the type (Komikku matches the same way).
fun List<MUAuthor>?.namesOfType(type: String): List<String> =
    orEmpty().filter { it.type?.contains(type) == true }.mapNotNull { it.name }
