package reikai.domain.novel.model

/**
 * A favorited [Novel] paired with its chapter count, for the browse duplicate-add dialog. Novel twin
 * of [tachiyomi.domain.manga.model.MangaWithChapterCount], type only.
 */
data class NovelWithChapterCount(
    val novel: Novel,
    val chapterCount: Long,
)
