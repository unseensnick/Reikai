package yokai.domain.novel

import yokai.domain.novel.models.NovelChapter

/**
 * Pure cross-source chapter stitcher for merged novel groups (Phase 8b), the novel analogue of
 * [yokai.domain.chapter.ChapterAggregation]. Given each grouped novel's chapters, produces ONE
 * unified list.
 *
 * **Trunk = the preferred source if one is in the group, else the source with the most chapters.**
 * (Unlike manga, novels have no scanlators, so raw chapter count is the right completeness measure;
 * and unlike manga, an absent chapter number is stored as `0f`, so only a *positive* number counts
 * as recognized.)
 *
 * Cross-source matching is by recognized chapter number, but that only works when the trunk actually
 * carries numbers. Many lnreader plugins don't, so every chapter lands at `0f`; in that case there's
 * no reliable key to merge on, so the unified view is simply the trunk's (fullest / preferred)
 * complete list. When the trunk IS numbered: the trunk's chapters are kept, every recognized number
 * it lacks is gap-filled from the next source that has it (one row per number), and siblings'
 * unnumbered chapters are dropped (they can't be matched). Each returned [NovelChapter] keeps its own
 * `novelId`, so the caller can read a chapter from its origin source. Output is unsorted.
 *
 * Stateless and side-effect-free for unit testing.
 */
object NovelChapterAggregation {

    /**
     * @param chaptersByNovel each grouped novel's id mapped to that novel's chapters.
     * @param sourceIdByNovel each grouped novel's id mapped to its source id (for the priority rank).
     * @param preferredSourceIds the global preferred-source ranking, highest priority first.
     * @return the unified chapter list (unsorted). For 0 or 1 novel, returns the input unchanged.
     */
    fun aggregate(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
    ): List<NovelChapter> {
        if (chaptersByNovel.size <= 1) return chaptersByNovel.values.firstOrNull().orEmpty()

        // Rank by preferred-source priority first (a ranked source wins the trunk regardless of
        // count), then chapter count desc, then novel id asc for a stable order.
        val ranked = chaptersByNovel.entries
            .map { (novelId, chapters) ->
                val prefRank = sourceIdByNovel[novelId]
                    ?.let { preferredSourceIds.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
                RankedSource(novelId, chapters, prefRank)
            }
            .sortedWith(
                compareBy<RankedSource> { it.prefRank }
                    .thenByDescending { it.chapters.size }
                    .thenBy { it.novelId },
            )

        // No numbers on the trunk -> no reliable cross-source key, so just show its full list.
        val trunk = ranked.first()
        if (trunk.chapters.none { it.isRecognizedNumber }) return trunk.chapters

        val unified = mutableListOf<NovelChapter>()
        val seenNumbers = HashSet<Float>()
        ranked.forEachIndexed { index, source ->
            val isTrunk = index == 0
            for (chapter in source.chapters) {
                when {
                    // One row per recognized number across the whole group.
                    chapter.isRecognizedNumber -> if (seenNumbers.add(chapter.chapterNumber)) unified.add(chapter)
                    // Unnumbered chapters can't be matched, so keep only the trunk's.
                    isTrunk -> unified.add(chapter)
                }
            }
        }
        return unified
    }

    // Absent novel chapter numbers are stored as 0f (LnPlugin ChapterItem.chapterNumber is nullable),
    // so only a positive number is a usable cross-source key.
    private val NovelChapter.isRecognizedNumber: Boolean
        get() = chapterNumber > 0f

    private class RankedSource(
        val novelId: Long,
        val chapters: List<NovelChapter>,
        val prefRank: Int,
    )
}
