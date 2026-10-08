package reikai.domain.library

import reikai.domain.merge.libraryLead
import reikai.domain.novel.model.LibraryNovel
import tachiyomi.domain.manga.model.Manga

/**
 * The member a merged manga series' library row leads on, which the collapse and the list export both
 * pick through. Ranked on [recognizedCounts] (manga id to distinct recognized numbers), the count the
 * manga stitch ranks its trunk on: a source listing each chapter once per scanlator must not lead on volume.
 */
fun <T> mangaLibraryLead(
    members: List<T>,
    memberRanking: List<Long>,
    preferredSourceIds: List<Long>,
    recognizedCounts: Map<Long, Long>,
    manga: (T) -> Manga,
): T = libraryLead(
    members,
    memberRanking,
    preferredSourceIds,
    id = { manga(it).id },
    sourceId = { manga(it).source },
    chapterCount = { recognizedCounts[manga(it).id] ?: 0L },
)

/** [mangaLibraryLead] for novels, ranked on rows: the novel stitch collapses no scanlator variants. */
fun novelLibraryLead(
    members: List<LibraryNovel>,
    memberRanking: List<Long>,
    preferredSourceIds: List<String>,
): LibraryNovel = libraryLead(
    members,
    memberRanking,
    preferredSourceIds,
    id = { it.novel.id },
    sourceId = { it.novel.source },
    chapterCount = { it.totalChapters },
)
