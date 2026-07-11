package reikai.domain.manga

import tachiyomi.domain.chapter.model.Chapter

/**
 * Pure cross-source chapter stitcher for merged manga groups. Given each sibling source's chapters,
 * produces ONE unified list:
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
 * its own [Chapter.mangaId], so the caller can read a chapter from its origin source. Output is
 * unsorted: callers apply their own sort.
 *
 * **Dedup key is the chapter number narrowed to [Float].** The source API stores
 * `SChapter.chapter_number` as a 32-bit float, so a source that reports a number hands back e.g.
 * `1.1f` (≈ 1.10000002384), while a source that doesn't falls through to [ChapterRecognition], which
 * parses in [Double] and yields exact `1.1`. Those differ by ~2.4e-8, so an exact-double key would
 * leave the same logical chapter duplicated across sources. Narrowing both to [Float] snaps them
 * onto one grid (`1.1.toFloat() == 1.1f`) while keeping real sub-chapters (`x.005`, `x.1`, `x.2`)
 * distinct, since their spacing is far wider than a float ULP at realistic chapter magnitudes.
 *
 * Stateless and side-effect-free so it can be unit-tested in isolation.
 */
object ChapterAggregation {

    /**
     * @param chaptersBySource each sibling manga's id mapped to that source's chapters.
     * @param sourceIdByManga each sibling manga's id mapped to its source id (for the priority rank).
     *   Empty (the default) means no source priority: pure distinct-count, unchanged behavior.
     * @param preferredSourceIds the global preferred-source ranking, highest priority first. A source
     *   on this list wins the trunk over distinct-count; unranked sources fall back to distinct-count
     *   among themselves.
     * @param gallerySourceMangaIds manga ids whose source treats each chapter as a whole standalone
     *   gallery (adult / metadata sources). Their chapters bypass the cross-source number dedup, since
     *   every gallery source numbers its primary chapter 1 and would otherwise collide across sources.
     * @return the unified chapter list (unsorted). For 0 or 1 source, returns the input unchanged.
     */
    fun aggregate(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long> = emptyMap(),
        preferredSourceIds: List<Long> = emptyList(),
        gallerySourceMangaIds: Set<Long> = emptySet(),
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
                    .thenBy { it.mangaId },
            )

        val unified = mutableListOf<Chapter>()
        val seenNumbers = HashSet<Float>()
        ranked.forEachIndexed { index, source ->
            val isTrunk = index == 0
            val isGallery = source.mangaId in gallerySourceMangaIds
            for (chapter in source.chapters) {
                when {
                    // A gallery source's chapters are each a whole standalone gallery / version, and
                    // every gallery source numbers its primary chapter 1, so cross-source number dedup
                    // would drop one source's gallery. Keep every gallery chapter instead of collapsing.
                    isGallery -> unified.add(chapter)
                    // One row per recognized number across the whole group: add() returns false when
                    // the number is already covered, collapsing scanlator variants and any number an
                    // earlier source already supplied. Narrowed to Float so a float-origin and a
                    // double-origin "1.1" key to the same value (see the class doc).
                    chapter.isRecognizedNumber -> if (seenNumbers.add(
                            chapter.chapterNumber.toFloat(),
                        )
                    ) {
                        unified.add(chapter)
                    }
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
            .map { it.chapterNumber.toFloat() }
            .distinct()
            .count()

    private class RankedSource(
        val mangaId: Long,
        val chapters: List<Chapter>,
        val distinctCount: Int,
        val prefRank: Int,
    )
}
