package reikai.domain.novel.model

import kotlin.random.Random

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

/**
 * Comparator over [LibraryNovel] for this sort. [randomSeed] only matters for
 * [NovelLibrarySort.Type.Random] (a stable per-seed shuffle, so the order holds across re-emits until
 * the seed changes). [trackerMeanScores] (rep novel id -> mean 0-10 score) only matters for
 * [NovelLibrarySort.Type.TrackerMean]; unscored novels fall to a default so they group at one end. The
 * comparator is a pure top-level function, so tracker scores are precomputed by the caller and passed in
 * rather than read off the novel. Title is the stable tiebreak for every mode.
 */
fun NovelLibrarySort.comparator(
    randomSeed: Long = 0L,
    trackerMeanScores: Map<Long, Double> = emptyMap(),
    // Novel id -> the group's deduplicated unread count, for merged entries. Passed in rather than read
    // off the novel because LibraryNovel derives unreadCount from its own chapter counts, which describe
    // one source. Falls back to the novel's own count when absent.
    unreadCounts: Map<Long, Long> = emptyMap(),
): Comparator<LibraryNovel> {
    val base: Comparator<LibraryNovel> = when (type) {
        NovelLibrarySort.Type.Alphabetical -> compareBy { it.novel.title.lowercase() }
        NovelLibrarySort.Type.LastRead -> compareBy { it.lastRead }
        NovelLibrarySort.Type.LastUpdate -> compareBy { it.novel.lastUpdate }
        NovelLibrarySort.Type.UnreadCount -> compareBy { unreadCounts[it.novel.id] ?: it.unreadCount }
        NovelLibrarySort.Type.TotalChapters -> compareBy { it.totalChapters }
        NovelLibrarySort.Type.LatestChapter -> compareBy { it.latestUpload }
        NovelLibrarySort.Type.ChapterFetchDate -> compareBy { it.chapterFetchedAt }
        NovelLibrarySort.Type.DateAdded -> compareBy { it.novel.dateAdded }
        NovelLibrarySort.Type.Downloaded -> compareBy { it.downloadCount }
        NovelLibrarySort.Type.TrackerMean -> compareBy { trackerMeanScores[it.novel.id] ?: -1.0 }
        NovelLibrarySort.Type.Random -> compareBy { Random(randomSeed + it.id).nextInt() }
    }
    val withTiebreak = base.thenBy { it.novel.title.lowercase() }
    return if (isAscending) withTiebreak else withTiebreak.reversed()
}
