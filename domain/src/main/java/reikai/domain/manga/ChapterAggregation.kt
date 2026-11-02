package reikai.domain.manga

import reikai.domain.merge.GroupRanking
import reikai.domain.merge.MergedChapterOrder
import reikai.domain.merge.MergedChapters
import reikai.domain.merge.stampedReadingOrder
import reikai.domain.merge.unstitchedChapters
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
     * The group's chapter list in reading order, plus which merged chapter every input chapter belongs
     * to, for the callers that have to count the group once rather than render it. Each returned
     * [Chapter] keeps its own [Chapter.mangaId], so a caller can read it from its origin source, and has
     * its `sourceOrder` restamped as its position in that order, the only index comparable across the
     * group.
     *
     * @param preferredSourceIds the global ranking, highest first. A listed source wins the trunk over
     *   distinct-count; unranked ones fall back to distinct-count among themselves.
     * @param gallerySourceMangaIds ids whose source treats each chapter as a standalone gallery. Their
     *   chapters bypass the number dedup, since every gallery source numbers its first chapter 1.
     * @param memberRanking a per-group override ranking members by position, which ignores
     *   [preferredSourceIds] so two members sharing a source still order distinctly.
     * @return for 0 or 1 source, the input unchanged.
     */
    fun merge(
        chaptersBySource: Map<Long, List<Chapter>>,
        sourceIdByManga: Map<Long, Long> = emptyMap(),
        preferredSourceIds: List<Long> = emptyList(),
        gallerySourceMangaIds: Set<Long> = emptySet(),
        memberRanking: List<Long> = emptyList(),
    ): MergedChapters<Chapter> {
        if (chaptersBySource.size <= 1) {
            return unstitchedChapters(chaptersBySource.values.firstOrNull().orEmpty()) { it.id }
        }

        val ranked = ranking.walkOrder(chaptersBySource, sourceIdByManga, preferredSourceIds, memberRanking)

        val order = MergedChapterOrder<Chapter> { chapter ->
            // Narrowed to Float so a float-origin and a double-origin "1.1" key to the same value
            // (see the class doc). Null where nothing was recognized, which matches nothing, and for
            // a gallery chapter, whose number identifies nothing: a later source's chapter 1 would
            // otherwise be taken for a copy of the gallery's work.
            chapter.chapterNumber.toFloat()
                .takeIf { chapter.isRecognizedNumber && chapter.mangaId !in gallerySourceMangaIds }
        }
        ranked.forEachIndexed { index, source ->
            order.startSource()
            val isTrunk = index == 0
            val isGallery = source.memberId in gallerySourceMangaIds
            for (chapter in source.chapters) {
                // A gallery source's chapters are each a whole standalone gallery / version, and
                // every gallery source numbers its primary chapter 1, so cross-source number dedup
                // would drop one source's gallery. Keep every gallery chapter instead of collapsing.
                if (isGallery) {
                    order.place(chapter)
                    continue
                }
                if (!chapter.isRecognizedNumber) {
                    // Unrecognized numbers can't be matched, so keep only the trunk's.
                    if (isTrunk) order.place(chapter)
                    continue
                }
                // One row per recognized number across the whole group, collapsing scanlator variants
                // and any number an earlier source already supplied. A covered number is not dropped
                // silently: it carries this source's place in the order forward.
                val existing = order.positionOf(chapter)
                if (existing >= 0) {
                    order.followTo(existing, chapter)
                    continue
                }
                order.place(chapter)
            }
        }
        val stitched = order.result()
        val merged = stampedReadingOrder(stitched.merged) { chapter, position -> chapter.copy(sourceOrder = position) }
        return MergedChapters(merged, stitched.units { it.id })
    }

    /** The stitch's ranking, counting distinct recognized numbers. */
    val ranking = GroupRanking<Chapter, Long> { recognizedChapterCount(it).toLong() }

    /** The count [ranking] orders a source by: its distinct recognized chapter numbers. */
    fun recognizedChapterCount(chapters: List<Chapter>): Int =
        distinctChapterNumberCount(chapters.filter { it.isRecognizedNumber }.map { it.chapterNumber })

    /** How many chapters [numbers] name, each narrowed to Float as the stitch keys them. */
    fun distinctChapterNumberCount(numbers: List<Double>): Int = numbers.distinctBy { it.toFloat() }.size
}
