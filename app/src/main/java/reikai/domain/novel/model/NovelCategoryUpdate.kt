package reikai.domain.novel.model

/**
 * Partial-update DTO for a novel category: a null field leaves that column unchanged (the `coalesce`
 * in the shared `update` query keeps the stored value).
 */
data class NovelCategoryUpdate(
    val id: Long,
    val name: String? = null,
    val order: Long? = null,
    val flags: Long? = null,
)
