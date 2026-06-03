package yokai.domain.novel.models

import eu.kanade.tachiyomi.domain.manga.models.Manga
import yokai.domain.novel.NovelPreferences

/**
 * Per-novel chapter sort / filter / display settings, packed into [Novel.chapterFlags] using the
 * same bit layout as the manga side ([Manga]'s CHAPTER_* constants), so the shared
 * DetailsFilterSortSheet, the persisted value, and these resolvers all agree on the encoding.
 *
 * A novel either uses its own (local) settings or falls back to the global defaults in
 * [NovelPreferences]; the CHAPTER_SORT_LOCAL / CHAPTER_FILTER_LOCAL bits pick which. Novel is an
 * immutable data class, so mutation goes through [setFlag] which returns the new flags Int.
 *
 * Lives in the app module (not domain) because it depends on [NovelPreferences], which the app
 * module owns, while keeping the `yokai.domain.novel.models` package alongside [Novel].
 */

val Novel.sorting: Int get() = chapterFlags and Manga.CHAPTER_SORTING_MASK
val Novel.sortDescending: Boolean
    get() = chapterFlags and Manga.CHAPTER_SORT_MASK == Manga.CHAPTER_SORT_DESC
val Novel.readFilter: Int get() = chapterFlags and Manga.CHAPTER_READ_MASK
val Novel.bookmarkedFilter: Int get() = chapterFlags and Manga.CHAPTER_BOOKMARKED_MASK
val Novel.hideChapterTitles: Boolean
    get() = chapterFlags and Manga.CHAPTER_DISPLAY_MASK == Manga.CHAPTER_DISPLAY_NUMBER
val Novel.usesLocalSort: Boolean
    get() = chapterFlags and Manga.CHAPTER_SORT_LOCAL_MASK == Manga.CHAPTER_SORT_LOCAL
val Novel.usesLocalFilter: Boolean
    get() = chapterFlags and Manga.CHAPTER_FILTER_LOCAL_MASK == Manga.CHAPTER_FILTER_LOCAL

// Effective values: the novel's own setting when its local bit is set, else the global default.
fun Novel.effectiveSorting(prefs: NovelPreferences): Int =
    if (usesLocalSort) sorting else prefs.defaultChapterSortOrder().get()
fun Novel.effectiveSortDescending(prefs: NovelPreferences): Boolean =
    if (usesLocalSort) sortDescending else prefs.defaultChapterSortDescending().get()
fun Novel.effectiveReadFilter(prefs: NovelPreferences): Int =
    if (usesLocalFilter) readFilter else prefs.defaultChapterFilterUnread().get()
fun Novel.effectiveBookmarkedFilter(prefs: NovelPreferences): Int =
    if (usesLocalFilter) bookmarkedFilter else prefs.defaultChapterFilterBookmarked().get()
fun Novel.effectiveHideChapterTitles(prefs: NovelPreferences): Boolean =
    if (usesLocalSort) hideChapterTitles else prefs.defaultChapterHideTitles().get()

/** Replace the [mask] bits of [flags] with [flag]. */
fun setFlag(flags: Int, flag: Int, mask: Int): Int = (flags and mask.inv()) or (flag and mask)

/** Sort + filter a chapter list for display, using the novel's effective settings. */
fun List<NovelChapter>.sortedAndFiltered(novel: Novel, prefs: NovelPreferences): List<NovelChapter> {
    val read = novel.effectiveReadFilter(prefs)
    val bookmarked = novel.effectiveBookmarkedFilter(prefs)
    val filtered = filter { ch ->
        val readOk = when (read) {
            Manga.CHAPTER_SHOW_UNREAD -> !ch.read
            Manga.CHAPTER_SHOW_READ -> ch.read
            else -> true
        }
        val bookmarkOk = when (bookmarked) {
            Manga.CHAPTER_SHOW_BOOKMARKED -> ch.bookmark
            Manga.CHAPTER_SHOW_NOT_BOOKMARKED -> !ch.bookmark
            else -> true
        }
        readOk && bookmarkOk
    }
    val comparator: Comparator<NovelChapter> = when (novel.effectiveSorting(prefs)) {
        Manga.CHAPTER_SORTING_NUMBER -> compareBy { it.chapterNumber }
        Manga.CHAPTER_SORTING_UPLOAD_DATE -> compareBy { it.dateUpload }
        else -> compareBy { it.sourceOrder }
    }
    val sorted = filtered.sortedWith(comparator)
    return if (novel.effectiveSortDescending(prefs)) sorted.reversed() else sorted
}
