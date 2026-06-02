package yokai.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter

/**
 * Pure cross-source chapter stitcher for merged manga groups (Phase 6.3). Given each sibling
 * source's chapters, produces ONE unified list:
 *
 * 1. **Trunk = the source with the most _distinct recognized_ chapter numbers** (not the most rows).
 *    Counting distinct numbers (so a source listing one chapter under several scanlators collapses to
 *    one number for the count) stops a scanlator-heavy source from winning the trunk on raw row count
 *    alone. This is the Comick case: it lists each chapter many times but covers fewer real numbers.
 * 2. **Gap-fill:** every recognized number the trunk lacks is borrowed from the next source (in
 *    distinct-count order) that has it, one representative per number.
 *
 * The trunk's own chapters are kept as-is (including its scanlator variants and any
 * unrecognized-number chapters); sibling chapters with an unrecognized number (< 0) are dropped
 * since they can't be matched by number. Each returned [Chapter] keeps its own `manga_id`, so the
 * caller can read a chapter from its origin source. Output is unsorted: callers apply their own sort.
 *
 * Stateless and side-effect-free so it can be unit-tested in isolation. The comparison borrowed from
 * Komikku's GetMergedChaptersByMangaId is only the pure "same recognized number, different source"
 * idea; nothing here touches a merged-source DB model.
 */
object ChapterAggregation {

    /**
     * @param chaptersBySource each sibling manga's id mapped to that source's chapters.
     * @return the unified chapter list (unsorted). For 0 or 1 source, returns the input unchanged.
     */
    fun aggregate(chaptersBySource: Map<Long, List<Chapter>>): List<Chapter> {
        if (chaptersBySource.size <= 1) return chaptersBySource.values.firstOrNull().orEmpty()

        // Rank by distinct recognized numbers desc; tie-break by manga id asc so the result is
        // deterministic (and so gap-fill draws from sources in a stable order).
        val ranked = chaptersBySource.entries
            .map { (mangaId, chapters) -> RankedSource(mangaId, chapters, distinctRecognizedCount(chapters)) }
            .sortedWith(compareByDescending<RankedSource> { it.distinctCount }.thenBy { it.mangaId })

        val trunk = ranked.first()
        val unified = trunk.chapters.toMutableList()
        val seenNumbers = trunk.chapters.asSequence()
            .filter { it.isRecognizedNumber }
            .map { it.chapter_number }
            .toHashSet()

        for (source in ranked.drop(1)) {
            for (chapter in source.chapters) {
                // add() returns false when the number is already covered, which collapses both
                // gap-filled scanlator duplicates and numbers already held by the trunk.
                if (chapter.isRecognizedNumber && seenNumbers.add(chapter.chapter_number)) {
                    unified.add(chapter)
                }
            }
        }
        return unified
    }

    private fun distinctRecognizedCount(chapters: List<Chapter>): Int =
        chapters.asSequence()
            .filter { it.isRecognizedNumber }
            .map { it.chapter_number }
            .distinct()
            .count()

    private class RankedSource(
        val mangaId: Long,
        val chapters: List<Chapter>,
        val distinctCount: Int,
    )
}
