package reikai.domain.merge.model

/**
 * One library member of a merge group and what its cover is loaded from, for the cover a merged row
 * falls back to. [S] is the content type's source id: a `Long` for manga, a plugin id for novels.
 */
data class MemberCover<S>(
    val memberId: Long,
    val groupId: Long,
    val sourceId: S,
    val url: String?,
    val lastModified: Long,
)
