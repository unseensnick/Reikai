package reikai.data.manga

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga

/**
 * A source's chapter list as a sync stores it: one row per url, before and after the source's prepare
 * hook, names without the series title in front, numbers recognized. Anything counting a source's list
 * outside a sync counts this, so its numbers match what a commit stores.
 */
fun List<SChapter>.toSourceChapters(manga: Manga, source: Source): List<Chapter> =
    distinctBy { it.url }
        .mapIndexed { i, sChapter ->
            Chapter.create()
                .copyFromSChapter(sChapter)
                .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                .copy(mangaId = manga.id, sourceOrder = i.toLong())
                .prepared(manga, source)
        }
        // The hook may rewrite a url onto another chapter's; the first one listed keeps it.
        .distinctBy { it.url }
        .map { it.copy(chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, it.name, it.chapterNumber)) }

private fun Chapter.prepared(manga: Manga, source: Source): Chapter {
    if (source !is HttpSource) return this
    val sChapter = toSChapter()
    @Suppress("DEPRECATION")
    source.prepareNewChapter(sChapter, manga.toSManga())
    return copyFromSChapter(sChapter)
}
