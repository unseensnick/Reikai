package eu.kanade.tachiyomi.data.track.comick.dto

import kotlinx.serialization.Serializable

/**
 * The creators on Comick's public comic page (`v1.0/comic/{hid}`), which the integrations API the
 * tracker signs in to does not return. Names are joined as every other tracker's autofill joins them.
 */
@Serializable
data class ComickComicDetails(
    val authors: List<ComickCreator> = emptyList(),
    val artists: List<ComickCreator> = emptyList(),
) {
    fun authorNames(): String? = authors.names()

    fun artistNames(): String? = artists.names()

    private fun List<ComickCreator>.names(): String? =
        mapNotNull { it.name?.trim()?.ifBlank { null } }.joinToString(", ").ifEmpty { null }
}

@Serializable
data class ComickCreator(val name: String? = null)
