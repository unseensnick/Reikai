package exh.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

/**
 * A built-in gallery source's update: the details [fetchManga] reads when asked, and one chapter, the
 * gallery itself under its own url, since a gallery is read through its own pages.
 */
suspend fun singleChapterGalleryUpdate(
    manga: SManga,
    chapters: List<SChapter>,
    fetchDetails: Boolean,
    fetchChapters: Boolean,
    fetchManga: suspend () -> SManga,
): SMangaUpdate {
    val updatedManga = if (fetchDetails) fetchManga() else manga
    val updatedChapters = if (fetchChapters) {
        listOf(
            SChapter.create().apply {
                url = manga.url
                name = "Chapter"
                chapter_number = 1f
            },
        )
    } else {
        chapters
    }
    return SMangaUpdate(updatedManga, updatedChapters)
}
