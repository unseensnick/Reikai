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
    /**
     * Volume / section / arc label this chapter belongs to (e.g., "Volume 3", "Arc 2"). Populated
     * from the plugin's [yokai.novel.host.ChapterItem.page] when the source organizes chapters in
     * pages; empty string when the source doesn't expose this metadata. Rendered as a chapter-list
     * sub-header in the future Novels tab.
     */
    val page: String = "",
    /**
     * True when the chapter's text was downloaded for offline reading. The on-disk HTML file is the
     * read-back source of truth; this flag is the fast signal the chapter list uses to render the
     * download chip. Defaulted so non-DB construction sites (backup, sync) don't have to set it.
     */
    val isDownloaded: Boolean = false,
)
