package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.chapter.ChapterSortPick
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetMangaChapterFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetDownloadedFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(manga.id) {
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_DOWNLOADED_MASK)
            },
        )
    }

    suspend fun awaitSetUnreadFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(manga.id) {
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_UNREAD_MASK)
            },
        )
    }

    suspend fun awaitSetBookmarkFilter(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(manga.id) {
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_BOOKMARKED_MASK)
            },
        )
    }

    suspend fun awaitSetDisplayMode(manga: Manga, flag: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(manga.id) {
                chapterFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_DISPLAY_MASK)
            },
        )
    }

    suspend fun awaitSetSortingModeOrFlipOrder(manga: Manga, flag: Long): Boolean {
        // RK --> the flip-or-ascending rule is ChapterSortPick, which the novel chapter settings share
        val descending = ChapterSortPick.descendingAfter(manga.sorting, manga.sortDescending(), flag)
        val newFlags = manga.chapterFlags
            .setFlag(flag, Manga.CHAPTER_SORTING_MASK)
            .setFlag(if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DIR_MASK)
        // RK <--
        return mangaRepository.update(
            MangaUpdate(manga.id) {
                chapterFlags = newFlags
            },
        )
    }

    suspend fun awaitSetAllFlags(
        mangaIds: List<Long>,
        unreadFilter: Long,
        downloadedFilter: Long,
        bookmarkedFilter: Long,
        sortingMode: Long,
        sortingDirection: Long,
        displayMode: Long,
    ): Boolean {
        val flags = 0L.setFlag(unreadFilter, Manga.CHAPTER_UNREAD_MASK)
            .setFlag(downloadedFilter, Manga.CHAPTER_DOWNLOADED_MASK)
            .setFlag(bookmarkedFilter, Manga.CHAPTER_BOOKMARKED_MASK)
            .setFlag(sortingMode, Manga.CHAPTER_SORTING_MASK)
            .setFlag(sortingDirection, Manga.CHAPTER_SORT_DIR_MASK)
            .setFlag(displayMode, Manga.CHAPTER_DISPLAY_MASK)
        return mangaRepository.updateAll(mangaIds.map { MangaUpdate(it) { chapterFlags = flags } })
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
