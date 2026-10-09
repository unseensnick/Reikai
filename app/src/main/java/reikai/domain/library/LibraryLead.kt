package reikai.domain.library

import reikai.domain.merge.libraryRanking
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
): T = mangaLibraryRanking(members, memberRanking, preferredSourceIds, recognizedCounts, manga).first()

/** Every member in the order [mangaLibraryLead] ranks them, the lead first. */
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

/** [mangaLibraryLead] for novels, ranked on rows: the novel stitch collapses no scanlator variants. */
fun novelLibraryLead(
    members: List<LibraryNovel>,
    memberRanking: List<Long>,
    preferredSourceIds: List<String>,
): LibraryNovel = novelLibraryRanking(members, memberRanking, preferredSourceIds).first()

/** Every member in the order [novelLibraryLead] ranks them, the lead first. */
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
