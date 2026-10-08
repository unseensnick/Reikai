package reikai.domain.novel

import reikai.domain.chapter.isRecognizedChapterNumber
import reikai.domain.merge.MergedChapterOrder
import reikai.domain.merge.MergedChapters
import reikai.domain.merge.sourcePriority
import reikai.domain.merge.stampedReadingOrder
import reikai.domain.merge.stitchOrder
import reikai.domain.merge.trunkOrder
import reikai.domain.merge.unstitchedChapters
import reikai.domain.novel.model.NovelChapter

/**
 * Pure cross-source chapter stitcher for merged novel groups, the novel analogue of
 * [reikai.domain.manga.ChapterAggregation]. Trunk = the preferred source if one is in the group, else the
 * source with the most chapters. Chapters match across sources by [matchKey]: normalized TITLE TEXT when
 * the name has any, else the recognized number. Title-first survives the off-by-one numbers sources
 * disagree on and the title-only MTL sources that ship no number. Each [NovelChapter] keeps its own
 * `novelId`, so it can be read from its origin source.
 */
object NovelChapterAggregation {

    /**
     * The group's chapter list in reading order, plus which merged chapter every input chapter belongs
     * to, for the callers that have to count the group once rather than render it. Each chapter's
     * `sourceOrder` is restamped as its position in that order, which is the only index comparable
     * across the group.
     *
     * @param chaptersByNovel each grouped novel's id mapped to that novel's chapters.
     * @param sourceIdByNovel each grouped novel's id mapped to its source id (for the priority rank).
     * @param preferredSourceIds the global preferred-source ranking, highest priority first.
     * @param memberRanking a per-group override: the member novel ids in the group's trunk order. When
     *   non-empty it ranks members by position and [preferredSourceIds] is ignored, so two members
     *   sharing a source still order distinctly. Empty uses the source list.
     * @return for 0 or 1 novel, the input unchanged.
     */
    fun merge(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): MergedChapters<NovelChapter> {
        if (chaptersByNovel.size <= 1) {
            return unstitchedChapters(chaptersByNovel.values.firstOrNull().orEmpty()) { it.id }
        }

        val ranked = stitchOrder(rank(chaptersByNovel, sourceIdByNovel, preferredSourceIds, memberRanking)) {
            it.chapters.isNotEmpty()
        }

        // No usable keys on the trunk -> no reliable cross-source matching, so just show its full list.
        val trunk = ranked.first()
        if (trunk.chapters.none { matchKey(it) != null }) return unstitchedChapters(trunk.chapters) { it.id }

        val order = MergedChapterOrder(
            isTitle = { (it as String).startsWith(TITLE_KEY_PREFIX) },
            boundsBackwardMatch = true,
            boundsForwardMatch = true,
            keyOf = ::matchKey,
        )
        ranked.forEachIndexed { index, source ->
            order.startSource(source.chapters)
            val isTrunk = index == 0
            for (chapter in source.chapters) {
                // Keep every trunk chapter: the novel stitch collapses no source's own rows, so two
                // sharing a title stay two, a multi-branch source's group copies included (parked.md).
                if (isTrunk) {
                    order.place(chapter)
                    continue
                }
                val existing = order.positionOf(chapter)
                // Already covered, so this source carries on from where its copy sits.
                if (existing >= 0) {
                    order.followTo(existing, chapter)
                    continue
                }
                // Unkeyable siblings drop: nothing identifies them and nothing places them. A new
                // title may be a chapter this source alone has, or one the trunk names by number
                // only ("685 Chapter 685"), and a number is counted differently per source, so
                // position decides both: a run matching the one already there is the same chapters.
                if (matchKey(chapter) != null) order.defer(chapter)
            }
        }
        val stitched = order.result()
        val merged = stampedReadingOrder(stitched.merged) { chapter, position -> chapter.copy(sourceOrder = position) }
        return MergedChapters(merged, stitched.units { it.id })
    }

    /**
     * The member novel ids in trunk order (first = trunk), the same ranking [merge] applies. Lets the
     * manage-sources dialog badge the primary source without stitching the whole chapter list.
     */
    fun rankedMemberIds(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
        memberRanking: List<Long> = emptyList(),
    ): List<Long> = rank(chaptersByNovel, sourceIdByNovel, preferredSourceIds, memberRanking).map { it.novelId }

    // The shared trunk order, counting rows: the novel stitch collapses no scanlator variants.
    private fun rank(
        chaptersByNovel: Map<Long, List<NovelChapter>>,
        sourceIdByNovel: Map<Long, String>,
        preferredSourceIds: List<String>,
        memberRanking: List<Long>,
    ): List<RankedSource> = chaptersByNovel.entries
        .map { (novelId, chapters) ->
            val prefRank = sourcePriority(novelId, sourceIdByNovel[novelId], preferredSourceIds, memberRanking)
            RankedSource(novelId, chapters, prefRank)
        }
        .sortedWith(trunkOrder({ it.prefRank }, { it.chapters.size.toLong() }, { it.novelId }))

    /**
     * The cross-source identity of a chapter, or null when it has none. Prefers the normalized title
     * text (drops "chapter"/"vol" label words, the leading chapter-number tokens, and punctuation);
     * falls back to a number for numeric-only names that have a recognized one. What the stitch pairs sources'
     * chapters on before it places the rest by position.
     */
    fun matchKey(chapter: NovelChapter): String? {
        val title = normalizedTitle(chapter.name)
        if (title.isNotEmpty()) return "$TITLE_KEY_PREFIX$title"
        if (!isRecognizedChapterNumber(chapter.chapterNumber)) return null
        return "n:${shownNumber(chapter.name) ?: chapter.chapterNumber}"
    }

    // A source may store its own list position as the number ("Chapter 320" stored as 322), so the
    // one number a wordless name shows is the identity; a name showing several keeps the stored one.
    private fun shownNumber(name: String): Double? =
        nameNumber.findAll(name).map { it.value.toDouble() }.distinct().singleOrNull()

    /** Marks a key built from title text rather than from a number, which is the identity that
     *  survives two sources counting differently. */
    private const val TITLE_KEY_PREFIX = "t:"

    private val labelWords = setOf(
        "chapter", "ch", "chap", "episode", "ep", "part", "pt", "vol", "volume", "book", "season", "s",
    )
    private val numberToken = Regex("""^[0-9]+(\.[0-9]+)?$""")
    private val nameNumber = Regex("""[0-9]+(\.[0-9]+)?""")
    private val nonAlphanumeric = Regex("""[^a-z0-9]+""")

    // Removed rather than turned into a space: one site writes "Courts" for "Court's", and the split
    // "court s" lost its "s" as a label word. Some sites put a double quote or a replacement character
    // where the apostrophe goes, or zero-width characters inside a word.
    private val elided = Regex("""['`\u00B4\u2018\u2019\u201C\u201D\uFFFD\p{Cf}]""")

    // Drops label words anywhere and only the LEADING chapter-number tokens; a number that follows a
    // title word is kept, so "Pleasureful Repeats 2" stays distinct from "Pleasureful Repeats" (else a
    // sequel-titled sibling chapter collides and gets deduped out of the unified list), while
    // "Chapter 1 - 0 Surviving Just to Die" and "0 Surviving Just to Die" still both reduce to
    // "surviving just to die".
    private fun normalizedTitle(name: String): String {
        val out = mutableListOf<String>()
        var seenWord = false
        for (token in name.lowercase().replace(elided, "").replace(nonAlphanumeric, " ").trim().split(' ')) {
            when {
                token.isEmpty() || token in labelWords -> {}
                numberToken.matches(token) -> if (seenWord) out.add(token)
                else -> {
                    out.add(token)
                    seenWord = true
                }
            }
        }
        return out.joinToString(" ")
    }

    private class RankedSource(
        val novelId: Long,
        val chapters: List<NovelChapter>,
        val prefRank: Int,
    )
}
