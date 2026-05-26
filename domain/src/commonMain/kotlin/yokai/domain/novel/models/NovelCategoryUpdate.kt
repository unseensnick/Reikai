package yokai.domain.novel.models

data class NovelCategoryUpdate(
    val id: Long,
    val name: String? = null,
    val novelOrder: String? = null,
    val order: Long? = null,
    val flags: Long? = null,
)
