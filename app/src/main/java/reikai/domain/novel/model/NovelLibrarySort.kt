package reikai.domain.novel.model

import reikai.domain.library.LibrarySortMode

/**
 * Per-category novel library sort. Mirrors Mihon's [tachiyomi.domain.library.model.LibrarySort] bit
 * layout (type in bits 2-5, ascending in bit 6) so it serializes into [NovelCategory.flags] the same
 * way the manga side serializes into `Category.flags`. Mihon's TrackerMean bit (0b100000) is reused for
 * a Downloaded-count sort (the reuse predates novel tracking); the novel [Type.TrackerMean] sort takes a
 * free slot (0b100100) instead.
 *
 * One addition over Mihon: a [CUSTOMIZED] sentinel bit (bit 0). A category uses its own flags only
 * once a sort has been explicitly set; until then it follows the library default. This is what lets a
 * fresh category (flags 0) follow the default instead of decoding to Alphabetical-Descending, while
 * still allowing Alphabetical-Descending to be set explicitly.
 */
data class NovelLibrarySort(
    val type: Type,
    val isAscending: Boolean,
) {
    enum class Type(val flag: Long) {
        Alphabetical(0b000000),
        LastRead(0b000100),
        LastUpdate(0b001000),
        UnreadCount(0b001100),
        TotalChapters(0b010000),
        LatestChapter(0b010100),
        ChapterFetchDate(0b011000),
        DateAdded(0b011100),
        Downloaded(0b100000),
        TrackerMean(0b100100),
        Random(0b111100),
        ;

        companion object {
            const val MASK = 0b111100L

            fun fromFlag(flag: Long): Type = entries.find { it.flag == (flag and MASK) } ?: Alphabetical
        }
    }

    /** Encode into the bits stored in [NovelCategory.flags], with the customized sentinel set. */
    fun toFlag(): Long = CUSTOMIZED or type.flag or if (isAscending) ASCENDING else 0L

    companion object {
        const val ASCENDING = 0b1000000L
        const val CUSTOMIZED = 0b0000001L

        /** Every bit this sort owns in `NovelCategory.flags`; the rest (e.g. the hidden bit) is preserved. */
        const val FLAGS_MASK = CUSTOMIZED or Type.MASK or ASCENDING

        val default = NovelLibrarySort(Type.Alphabetical, isAscending = true)

        fun fromFlag(flag: Long): NovelLibrarySort =
            NovelLibrarySort(Type.fromFlag(flag), (flag and ASCENDING) == ASCENDING)

        /** A category uses its own flags only once a sort was explicitly set; else the library default. */
        fun forCategory(flags: Long, default: NovelLibrarySort): NovelLibrarySort =
            if ((flags and CUSTOMIZED) != 0L) fromFlag(flags) else default
    }
}

/** Resolve this novel sort key to the neutral mode the shared comparator understands. */
fun NovelLibrarySort.Type.toSortMode(): LibrarySortMode = when (this) {
    NovelLibrarySort.Type.Alphabetical -> LibrarySortMode.Alphabetical
    NovelLibrarySort.Type.LastRead -> LibrarySortMode.LastRead
    NovelLibrarySort.Type.LastUpdate -> LibrarySortMode.LastUpdate
    NovelLibrarySort.Type.UnreadCount -> LibrarySortMode.UnreadCount
    NovelLibrarySort.Type.TotalChapters -> LibrarySortMode.TotalChapters
    NovelLibrarySort.Type.LatestChapter -> LibrarySortMode.LatestChapter
    NovelLibrarySort.Type.ChapterFetchDate -> LibrarySortMode.ChapterFetchDate
    NovelLibrarySort.Type.DateAdded -> LibrarySortMode.DateAdded
    NovelLibrarySort.Type.Downloaded -> LibrarySortMode.Downloaded
    NovelLibrarySort.Type.TrackerMean -> LibrarySortMode.TrackerMean
    NovelLibrarySort.Type.Random -> LibrarySortMode.Random
}

// The novel library sorts through the one shared binding over the library row
// (reikai.presentation.library.libraryItemSortFields), the same one the manga library uses, so this
// file only has to resolve the persisted flags to a neutral sort mode.
