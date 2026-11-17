package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.chapter.ChapterSortPick
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveDownloadedFilter
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveReadFilter
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import reikai.domain.novel.model.setNovelFlag
import reikai.domain.novel.model.usesLocalDisplay
import reikai.domain.novel.model.usesLocalFilter
import reikai.domain.novel.model.usesLocalSort

/**
 * Per-novel chapter sort / filter / display writes, twin of
 * [tachiyomi.domain.manga.interactor.SetMangaChapterFlags], pinned by [ChapterSortPick] for the one rule
 * the two share (the sort page's flip). Each setter recomputes the packed
 * [Novel.chapterFlags] from the current value and writes only that column via a [NovelUpdate].
 * Setting a sort / filter / display also flips the matching local-override bit so the novel uses its
 * own value instead of the global default.
 */
@Inject
class SetNovelChapterFlags(
    private val novelRepository: NovelRepository,
    private val novelPreferences: NovelPreferences,
) {

    /** Judged against what the list shows, which for a novel without its own sort is the global default. */
    suspend fun awaitSetSortingModeOrFlipOrder(novel: Novel, picked: Long): Boolean {
        val descending = ChapterSortPick.descendingAfter(
            shownSorting = novel.effectiveSorting(novelPreferences),
            shownDescending = novel.effectiveSortDescending(novelPreferences),
            picked = picked,
        )
        val direction = if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC
        var flags = setNovelFlag(novel.chapterFlags, picked, NovelChapterFlags.SORTING_MASK)
        flags = setNovelFlag(flags, direction, NovelChapterFlags.SORT_DIR_MASK)
        flags = setNovelFlag(flags, NovelChapterFlags.SORT_LOCAL, NovelChapterFlags.SORT_LOCAL_MASK)
        return novelRepository.update(NovelUpdate(novel.id) { chapterFlags = flags })
    }

    // One filter per pick, as manga's setters are: the other two keep what [novel], read fresh, shows, so two
    // quick picks made from one copy of the sheet cannot undo each other.
    suspend fun awaitSetReadFilter(novel: Novel, read: Long): Boolean = awaitSetFilters(
        novel,
        read,
        novel.effectiveBookmarkedFilter(novelPreferences),
        novel.effectiveDownloadedFilter(novelPreferences),
    )

    suspend fun awaitSetBookmarkedFilter(novel: Novel, bookmarked: Long): Boolean = awaitSetFilters(
        novel,
        novel.effectiveReadFilter(novelPreferences),
        bookmarked,
        novel.effectiveDownloadedFilter(novelPreferences),
    )

    suspend fun awaitSetDownloadedFilter(novel: Novel, downloaded: Long): Boolean = awaitSetFilters(
        novel,
        novel.effectiveReadFilter(novelPreferences),
        novel.effectiveBookmarkedFilter(novelPreferences),
        downloaded,
    )

    private suspend fun awaitSetFilters(novel: Novel, read: Long, bookmarked: Long, downloaded: Long): Boolean {
        var flags = setNovelFlag(novel.chapterFlags, read, NovelChapterFlags.READ_MASK)
        flags = setNovelFlag(flags, bookmarked, NovelChapterFlags.BOOKMARKED_MASK)
        flags = setNovelFlag(flags, downloaded, NovelChapterFlags.DOWNLOADED_MASK)
        flags = setNovelFlag(flags, NovelChapterFlags.FILTER_LOCAL, NovelChapterFlags.FILTER_LOCAL_MASK)
        return novelRepository.update(NovelUpdate(novel.id) { chapterFlags = flags })
    }

    suspend fun awaitSetHideTitles(novel: Novel, hide: Boolean): Boolean {
        val display = if (hide) NovelChapterFlags.DISPLAY_NUMBER else NovelChapterFlags.DISPLAY_NAME
        var flags = setNovelFlag(novel.chapterFlags, display, NovelChapterFlags.DISPLAY_MASK)
        flags = setNovelFlag(flags, NovelChapterFlags.DISPLAY_LOCAL, NovelChapterFlags.DISPLAY_LOCAL_MASK)
        return novelRepository.update(NovelUpdate(novel.id) { chapterFlags = flags })
    }

    /**
     * Make [shown]'s settings the global default, as manga's Set as default does. A library novel still
     * following the default takes the old one as its own first, since a manga keeps the default it was
     * added with, so only [applyToLibrary] moves the library.
     */
    suspend fun awaitSetAsDefault(shown: Novel, applyToLibrary: Boolean) {
        val sorting = shown.effectiveSorting(novelPreferences)
        val descending = shown.effectiveSortDescending(novelPreferences)
        val hideTitles = shown.effectiveHideChapterTitles(novelPreferences)
        val read = shown.effectiveReadFilter(novelPreferences)
        val bookmarked = shown.effectiveBookmarkedFilter(novelPreferences)
        val downloaded = shown.effectiveDownloadedFilter(novelPreferences)
        novelRepository.updateAll(novelRepository.getFavorites().mapNotNull(::pinnedToDefaults))
        with(novelPreferences) {
            defaultChapterSortOrder().set(sorting)
            defaultChapterSortDescending().set(descending)
            defaultChapterHideTitles().set(hideTitles)
            defaultChapterFilterUnread().set(read)
            defaultChapterFilterBookmarked().set(bookmarked)
            defaultChapterFilterDownloaded().set(downloaded)
        }
        if (applyToLibrary) awaitClearLibraryLocalOverrides()
    }

    /** Drop this novel's local sort, filter and display overrides so the global defaults apply again. */
    suspend fun awaitClearLocalOverrides(novel: Novel): Boolean =
        novelRepository.update(clearedOverrides(novel))

    /**
     * Return every library novel to the global defaults in one write, as manga's apply-to-library
     * does for favorites, so the library redraws once rather than once per novel.
     */
    suspend fun awaitClearLibraryLocalOverrides() {
        novelRepository.updateAll(novelRepository.getFavorites().map(::clearedOverrides))
    }

    // The settings [novel] follows from the default, written as its own; null when it follows none.
    private fun pinnedToDefaults(novel: Novel): NovelUpdate? = with(NovelChapterFlags) {
        val prefs = novelPreferences
        var flags = novel.chapterFlags
        if (!novel.usesLocalSort) {
            val direction = if (novel.effectiveSortDescending(prefs)) SORT_DESC else SORT_ASC
            flags = setNovelFlag(flags, novel.effectiveSorting(prefs), SORTING_MASK)
            flags = setNovelFlag(flags, direction, SORT_DIR_MASK)
            flags = setNovelFlag(flags, SORT_LOCAL, SORT_LOCAL_MASK)
        }
        if (!novel.usesLocalFilter) {
            flags = setNovelFlag(flags, novel.effectiveReadFilter(prefs), READ_MASK)
            flags = setNovelFlag(flags, novel.effectiveBookmarkedFilter(prefs), BOOKMARKED_MASK)
            flags = setNovelFlag(flags, novel.effectiveDownloadedFilter(prefs), DOWNLOADED_MASK)
            flags = setNovelFlag(flags, FILTER_LOCAL, FILTER_LOCAL_MASK)
        }
        if (!novel.usesLocalDisplay) {
            val display = if (novel.effectiveHideChapterTitles(prefs)) DISPLAY_NUMBER else DISPLAY_NAME
            flags = setNovelFlag(flags, display, DISPLAY_MASK)
            flags = setNovelFlag(flags, DISPLAY_LOCAL, DISPLAY_LOCAL_MASK)
        }
        if (flags == novel.chapterFlags) null else NovelUpdate(novel.id) { chapterFlags = flags }
    }

    private fun clearedOverrides(novel: Novel): NovelUpdate {
        var flags = setNovelFlag(novel.chapterFlags, 0L, NovelChapterFlags.SORT_LOCAL_MASK)
        flags = setNovelFlag(flags, 0L, NovelChapterFlags.FILTER_LOCAL_MASK)
        flags = setNovelFlag(flags, 0L, NovelChapterFlags.DISPLAY_LOCAL_MASK)
        return NovelUpdate(novel.id) { chapterFlags = flags }
    }
}
