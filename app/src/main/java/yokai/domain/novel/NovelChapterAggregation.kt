package yokai.domain.novel

import yokai.domain.novel.models.NovelChapter

/**
 * Pure cross-source chapter stitcher for merged novel groups (Phase 8b), the novel analogue of
 * [yokai.domain.chapter.ChapterAggregation]. Given each grouped novel's chapters, produces ONE
 * unified list:
 *
 * 1. **Trunk = the preferred source if one is in the group, else the source with the most
 *    _distinct recognized_ chapter numbers** (not the most rows). Distinct-number counting keeps a
 *    source that lists a chapter several times from winning the trunk on raw row count alone.
 * 2. **Gap-fill:** every recognized number the trunk lacks is borrowed from the next source (in
 *    rank, then distinct-count order) that has it.
 *
 * The result holds **one row per recognized chapter number**: a number already supplied by an
 * earlier source isn't repeated. The trunk's unrecognized-number chapters (< 0) are kept (they
 * can't be matched by number); siblings' unrecognized chapters are dropped to avoid unmatchable
 * duplicates. Each returned [NovelChapter] keeps its own `novelId`, so the caller can read a
 * chapter from its origin source. Output is unsorted: callers apply their own sort.
 *
 * Stateless and side-effect-free for unit testing.
 */
object NovelChapterAggregation {

    /**
     * @param chaptersByNovel each grouped novel's id mapped to that novel's chapters.
     * @param sourceIdByNovel each grouped novel's id mapped to its source id (for the priority rank).
     *   Empty (the default) means no source priority: pure distinct-count.
     * @param preferredSourceIds the global preferred-source ranking, highest priority first. A source
     *   on this list wins the trunk over distinct-count; unranked sources fall back to distinct-count.
     * @return the unified chapter list (unsorted). For 0 or 1 novel, returns the input unchanged.
     */
    fun aggregate(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
    ): List<NovelChapter> {
        if (chaptersByNovel.size <= 1) return chaptersByNovel.values.firstOrNull().orEmpty()

        // Rank by preferred-source priority first (a ranked source wins the trunk regardless of
        // count), then distinct recognized numbers desc, then novel id asc for a stable order.
        val ranked = chaptersByNovel.entries
            .map { (novelId, chapters) ->
                val prefRank = sourceIdByNovel[novelId]
                    ?.let { preferredSourceIds.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
                RankedSource(novelId, chapters, distinctRecognizedCount(chapters), prefRank)
            }
            .sortedWith(
                compareBy<RankedSource> { it.prefRank }
                    .thenByDescending { it.distinctCount }
                    .thenBy { it.novelId },
            )

        val unified = mutableListOf<NovelChapter>()
        val seenNumbers = HashSet<Float>()
        ranked.forEachIndexed { index, source ->
            val isTrunk = index == 0
            for (chapter in source.chapters) {
                when {
                    // One row per recognized number across the whole group.
                    chapter.isRecognizedNumber -> if (seenNumbers.add(chapter.chapterNumber)) unified.add(chapter)
                    // Unrecognized numbers can't be matched, so keep only the trunk's.
                    isTrunk -> unified.add(chapter)
                }
            }
        }
        return unified
    }

    private fun distinctRecognizedCount(chapters: List<NovelChapter>): Int =
        chapters.asSequence()
            .filter { it.isRecognizedNumber }
            .map { it.chapterNumber }
            .distinct()
            .count()

    private val NovelChapter.isRecognizedNumber: Boolean
        get() = chapterNumber >= 0f

    private class RankedSource(
        val novelId: Long,
        val chapters: List<NovelChapter>,
        val distinctCount: Int,
        val prefRank: Int,
    )
}
