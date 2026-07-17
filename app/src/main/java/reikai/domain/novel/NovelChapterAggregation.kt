package reikai.domain.novel

import reikai.domain.novel.model.NovelChapter

/**
 * Pure cross-source chapter stitcher for merged novel groups, the novel analogue of
 * [reikai.domain.manga.ChapterAggregation]. Given each grouped novel's chapters, produces ONE unified
 * list.
 *
 * **Trunk = the preferred source if one is in the group, else the source with the most chapters.**
 *
 * Chapters are matched across sources by [matchKey]: the normalized **title text** when the name has
 * any (e.g. "Chapter 1 - 0 Surviving Just To Die" and "0 Surviving Just to Die" both reduce to
 * `surviving just to die`), otherwise the recognized **chapter number** (for numeric-only names like
 * "Chapter 5"). Title-first is more forgiving than raw numbers, which often disagree across sources
 * (an off-by-one from a prologue), and survives the title-only MTL sources that ship no number at all.
 * **Every trunk chapter is kept** (novels have no scanlator variants, so each row is a distinct
 * chapter); a sibling chapter is added only when its key isn't already present (gap-fill), and a
 * sibling with no usable key is dropped (it can't be matched). So the unified list always contains at
 * least the whole trunk. Each returned [NovelChapter] keeps its own `novelId`, so the caller can read
 * a chapter from its origin source. Output is unsorted.
 *
 * Stateless and side-effect-free for unit testing.
 */
object NovelChapterAggregation {

    /**
     * @param chaptersByNovel each grouped novel's id mapped to that novel's chapters.
     * @param sourceIdByNovel each grouped novel's id mapped to its source id (for the priority rank).
     * @param preferredSourceIds the global preferred-source ranking, highest priority first.
     * @param memberRanking a per-group override: the member novel ids in the group's own trunk order.
     *   When non-empty it ranks members directly (by position here) and [preferredSourceIds] is ignored,
     *   so two members sharing a source still order distinctly. Empty (the default) uses the source list.
     * @return the unified chapter list (unsorted). For 0 or 1 novel, returns the input unchanged.
     */
    fun aggregate(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): List<NovelChapter> {
        if (chaptersByNovel.size <= 1) return chaptersByNovel.values.firstOrNull().orEmpty()

        val ranked = rank(chaptersByNovel, sourceIdByNovel, preferredSourceIds, memberRanking)

        // No usable keys on the trunk -> no reliable cross-source matching, so just show its full list.
        val trunk = ranked.first()
        if (trunk.chapters.none { matchKey(it) != null }) return trunk.chapters

        val unified = mutableListOf<NovelChapter>()
        val seenKeys = HashSet<String>()
        ranked.forEachIndexed { index, source ->
            val isTrunk = index == 0
            for (chapter in source.chapters) {
                val key = matchKey(chapter)
                when {
                    // Keep every trunk chapter (no intra-source collapse: novels have no scanlator
                    // variants, so distinct rows that happen to share a title are still distinct).
                    isTrunk -> {
                        unified.add(chapter)
                        if (key != null) seenKeys.add(key)
                    }
                    // Gap-fill a sibling chapter only when its key is new; unkeyable siblings drop.
                    key != null && seenKeys.add(key) -> unified.add(chapter)
                }
            }
        }
        return unified
    }

    /**
     * The member novel ids in trunk order (first = trunk), the same ranking [aggregate] applies. Lets the
     * manage-sources dialog badge the primary source without stitching the whole chapter list.
     */
    fun rankedMemberIds(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): List<Long> = rank(chaptersByNovel, sourceIdByNovel, preferredSourceIds, memberRanking).map { it.novelId }

    // Rank by preferred-source priority first (a ranked source wins the trunk regardless of count), then
    // chapter count desc, then novel id asc for a stable order. A per-group override ranks by member id
    // directly (memberRanking), bypassing the source list.
    private fun rank(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String>,
        preferredSourceIds: List<String>,
        memberRanking: List<Long>,
    ): List<RankedSource> = chaptersByNovel.entries
        .map { (novelId, chapters) ->
            val prefRank = if (memberRanking.isNotEmpty()) {
                memberRanking.indexOf(novelId).takeIf { it >= 0 } ?: Int.MAX_VALUE
            } else {
                sourceIdByNovel[novelId]
                    ?.let { preferredSourceIds.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: Int.MAX_VALUE
            }
            RankedSource(novelId, chapters, prefRank)
        }
        .sortedWith(
            compareBy<RankedSource> { it.prefRank }
                .thenByDescending { it.chapters.size }
                .thenBy { it.novelId },
        )

    /**
     * The cross-source identity of a chapter, or null when it has none. Prefers the normalized title
     * text (drops "chapter"/"vol" label words, standalone numbers, and punctuation); falls back to the
     * recognized chapter number for numeric-only names. Used for both the unified merge and the
     * read/bookmark propagation across grouped sources.
     */
    fun matchKey(chapter: NovelChapter): String? {
        val title = normalizedTitle(chapter.name)
        if (title.isNotEmpty()) return "t:$title"
        if (chapter.chapterNumber > 0.0) return "n:${chapter.chapterNumber}"
        return null
    }

    private val labelWords = setOf(
        "chapter", "ch", "chap", "episode", "ep", "part", "pt", "vol", "volume", "book", "season", "s",
    )
    private val numberToken = Regex("""^[0-9]+(\.[0-9]+)?$""")
    private val nonAlphanumeric = Regex("""[^a-z0-9]+""")

    private fun normalizedTitle(name: String): String =
        name.lowercase()
            .replace(nonAlphanumeric, " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() && it !in labelWords && !numberToken.matches(it) }
            .joinToString(" ")

    private class RankedSource(
        val novelId: Long,
        val chapters: List<NovelChapter>,
        val prefRank: Int,
    )
}
