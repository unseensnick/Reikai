package reikai.domain.novel.model

/**
 * Partial-update DTO for `novel_categories`: a null field leaves that column unchanged (the
 * `coalesce` in the `update` query keeps the stored value).
 */
data class NovelCategoryUpdate(
    val id: Long,
    val name: String? = null,
    val novelOrder: String? = null,
    val order: Long? = null,
    val flags: Long? = null,
)
