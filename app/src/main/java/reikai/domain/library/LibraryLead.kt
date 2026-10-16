package reikai.domain.library

import reikai.domain.merge.libraryRanking
import reikai.domain.novel.model.LibraryNovel
import tachiyomi.domain.manga.model.Manga

/**
 * A merged manga series' members in the order its library row ranks them, the lead first; the collapse,
 * the list export and Statistics all rank through it. Ranked on [recognizedCounts] (manga id to distinct
 * recognized numbers), the count the manga stitch ranks its trunk on: a source listing each chapter once
 * per scanlator must not lead on volume.
 */
fun <T> mangaLibraryRanking(
    members: List<T>,
    memberRanking: List<Long>,
    preferredSourceIds: List<Long>,
    recognizedCounts: Map<Long, Long>,
    manga: (T) -> Manga,
): List<T> = libraryRanking(
    members,
    memberRanking,
    preferredSourceIds,
    id = { manga(it).id },
    sourceId = { manga(it).source },
    chapterCount = { recognizedCounts[manga(it).id] ?: 0L },
)

/** [mangaLibraryRanking] for novels, ranked on rows: the novel stitch collapses no scanlator variants. */
fun novelLibraryRanking(
    members: List<LibraryNovel>,
    memberRanking: List<Long>,
    preferredSourceIds: List<String>,
): List<LibraryNovel> = libraryRanking(
    members,
    memberRanking,
    preferredSourceIds,
    id = { it.novel.id },
    sourceId = { it.novel.source },
    chapterCount = { it.totalChapters },
)
