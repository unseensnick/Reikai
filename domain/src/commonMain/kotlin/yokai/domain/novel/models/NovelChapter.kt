package yokai.domain.novel.models

/**
 * Domain mirror of the `novel_chapters` SQLDelight table. Two divergences from
 * [yokai.domain.chapter] semantics:
 *
 * * No `scanlator` field (novels don't have scanlator groups).
 * * `lastTextProgress` replaces the manga side's `lastPageRead` + `pagesLeft`. Stored as a
 *   hundredths-of-a-percent integer (0..10000) so the text reader can resume mid-paragraph.
 */
data class NovelChapter(
    val id: Long?,
    val novelId: Long,
    val url: String,
    val name: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastTextProgress: Int,
    val chapterNumber: Float,
    val sourceOrder: Long,
    val dateFetch: Long,
    val dateUpload: Long,
)
