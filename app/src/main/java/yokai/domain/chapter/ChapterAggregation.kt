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
 *    distinct-count order) that has it.
 *
 * The result holds **one row per recognized chapter number**: a source's own scanlator variants
 * collapse to one, and a number already supplied by an earlier source isn't repeated. The trunk's
 * unrecognized-number chapters (< 0) are kept (they can't be matched by number); siblings'
 * unrecognized chapters are dropped to avoid unmatchable duplicates. Each returned [Chapter] keeps
 * its own `manga_id`, so the caller can read a chapter from its origin source. Output is unsorted:
 * callers apply their own sort.
 *
 * Stateless and side-effect-free so it can be unit-tested in isolation. The comparison borrowed from
 * Komikku's GetMergedChaptersByMangaId is only the pure "same recognized number, different source"
 * idea; nothing here touches a merged-source DB model.
 */
object ChapterAggregation {

    /**
     * @param chaptersBySource each sibling manga's id mapped to that source's chapters.
     * @param sourceIdByManga each sibling manga's id mapped to its source id (for the priority rank).
     *   Empty (the default) means no source priority: pure distinct-count, unchanged behavior.
     * @param preferredSourceIds the global preferred-source ranking, highest priority first. A source
     *   on this list wins the trunk over distinct-count; unranked sources fall back to distinct-count
     *   among themselves.
     * @return the unified chapter list (unsorted). For 0 or 1 source, returns the input unchanged.
     */
    fun aggregate(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long> = emptyMap(),
        preferredSourceIds: List<Long> = emptyList(),
    ): List<Chapter> {
        if (chaptersBySource.size <= 1) return chaptersBySource.values.firstOrNull().orEmpty()

        // Rank by preferred-source priority first (a ranked source wins the trunk regardless of count),
        // then distinct recognized numbers desc, then manga id asc for a deterministic, stable order.
        // With no preferred sources every prefRank is MAX_VALUE, so this collapses to the prior rule.
        val ranked = chaptersBySource.entries
            .map { (mangaId, chapters) ->
                val prefRank = sourceIdByManga[mangaId]
                    ?.let { preferredSourceIds.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
                RankedSource(mangaId, chapters, distinctRecognizedCount(chapters), prefRank)
            }
            .sortedWith(
                compareBy<RankedSource> { it.prefRank }
                    .thenByDescending { it.distinctCount }
                    .thenBy { it.mangaId }
            )

        val unified = mutableListOf<Chapter>()
        val seenNumbers = HashSet<Float>()
        ranked.forEachIndexed { index, source ->
            val isTrunk = index == 0
            for (chapter in source.chapters) {
                when {
                    // One row per recognized number across the whole group: add() returns false when
                    // the number is already covered, collapsing scanlator variants and any number an
                    // earlier source already supplied.
                    chapter.isRecognizedNumber -> if (seenNumbers.add(chapter.chapter_number)) unified.add(chapter)
                    // Unrecognized numbers can't be matched, so keep only the trunk's.
                    isTrunk -> unified.add(chapter)
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
        val prefRank: Int,
    )
}
