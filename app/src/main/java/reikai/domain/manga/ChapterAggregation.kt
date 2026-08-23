package reikai.domain.manga

import tachiyomi.domain.chapter.model.Chapter

/**
 * Pure cross-source chapter stitcher for merged manga groups. Trunk = the source with the most DISTINCT recognized
 * chapter numbers, not the most rows, so a source listing one chapter under many scanlators cannot win on row count;
 * every number the trunk lacks is gap-filled from the next source in rank order. One row per recognized number,
 * keeping the trunk's unrecognized chapters and dropping siblings'. The dedup key is the number narrowed to [Float]:
 * a source-reported number is a 32-bit float where a parsed one is a double, so an exact double key duplicates a
 * chapter across sources, while float spacing still keeps real sub-chapters distinct.
 */
object ChapterAggregation {

    /**
     * Each returned [Chapter] keeps its own [Chapter.mangaId], so a caller can read it from its origin
     * source; the output is unsorted.
     *
     * @param preferredSourceIds the global ranking, highest first. A listed source wins the trunk over
     *   distinct-count; unranked ones fall back to distinct-count among themselves.
     * @param gallerySourceMangaIds ids whose source treats each chapter as a standalone gallery. Their
     *   chapters bypass the number dedup, since every gallery source numbers its first chapter 1.
     * @param memberRanking a per-group override ranking members by position, which ignores
     *   [preferredSourceIds] so two members sharing a source still order distinctly.
     * @return the unified list; for 0 or 1 source, the input unchanged.
     */
    fun aggregate(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long> = emptyMap(),
        preferredSourceIds: List<Long> = emptyList(),
        gallerySourceMangaIds: Set<Long> = emptySet(),
        memberRanking: List<Long> = emptyList(),
    ): List<Chapter> {
        if (chaptersBySource.size <= 1) return chaptersBySource.values.firstOrNull().orEmpty()

        val ranked = rank(chaptersBySource, sourceIdByManga, preferredSourceIds, memberRanking)

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

    /**
     * The member manga ids in trunk order (first = trunk), the same ranking [aggregate] applies. Lets the
     * manage-sources dialog badge the primary source without stitching the whole chapter list.
     */
    fun rankedMemberIds(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long> = emptyMap(),
        preferredSourceIds: List<Long> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): List<Long> = rank(chaptersBySource, sourceIdByManga, preferredSourceIds, memberRanking).map { it.mangaId }

    // Rank by preferred-source priority first (a ranked source wins the trunk regardless of count), then
    // distinct recognized numbers desc, then manga id asc for a deterministic, stable order. With no
    // preferred sources every prefRank is MAX_VALUE, collapsing to distinct-count. A per-group override
    // ranks by member id directly (memberRanking), bypassing the source list.
    private fun rank(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long>,
        preferredSourceIds: List<Long>,
        memberRanking: List<Long>,
    ): List<RankedSource> = chaptersBySource.entries
        .map { (mangaId, chapters) ->
            val prefRank = if (memberRanking.isNotEmpty()) {
                memberRanking.indexOf(mangaId).takeIf { it >= 0 } ?: Int.MAX_VALUE
            } else {
                sourceIdByManga[mangaId]
                    ?.let { preferredSourceIds.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            }
            RankedSource(mangaId, chapters, distinctRecognizedCount(chapters), prefRank)
        }
        .sortedWith(
            compareBy<RankedSource> { it.prefRank }
                .thenByDescending { it.distinctCount }
                .thenBy { it.mangaId },
        )

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
