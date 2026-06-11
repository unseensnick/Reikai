package eu.kanade.tachiyomi.data.track.bangumi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RK: wire types for the Bangumi `/v0/users/{username}/collections?subject_type=1` pull used by the
 * recommendation taste profile. Each item's `subject` carries `tags` inline. Collection `type`
 * matches Bangumi's status ids (1 plan, 2 completed, 3 reading, 4 on-hold, 5 dropped).
 */
@Serializable
data class BGMCollectionsResult(
    val data: List<BGMCollectionItem> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class BGMCollectionItem(
    @SerialName("subject_id")
    val subjectId: Long,
    val type: Int = 0,
    val rate: Int = 0,
    val subject: BGMCollectionSubject? = null,
)

@Serializable
data class BGMCollectionSubject(
    val id: Long = 0,
    val name: String = "",
    @SerialName("name_cn")
    val nameCn: String = "",
    val tags: List<BGMSubjectTag> = emptyList(),
)

@Serializable
data class BGMSubjectTag(
    val name: String,
)
