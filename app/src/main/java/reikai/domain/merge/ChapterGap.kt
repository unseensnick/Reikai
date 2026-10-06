package reikai.domain.merge

import reikai.domain.chapter.isRecognizedChapterNumber
import tachiyomi.domain.chapter.model.Chapter
import kotlin.math.floor

/**
 * How many chapters are missing between two neighbouring rows of a chapter list, or 0 when that
 * cannot be known. One rule for both content types.
 *
 * Subtracting two chapter numbers only answers the question when both numbers mean the same thing.
 * Two guards decide when they do not, and both err towards showing nothing: a marker that guesses is
 * worse than no marker.
 */
object ChapterGap {

    /** One side of a gap. [ownerId] is the library row the chapter belongs to, which for a grouped
     *  entry differs between sources. [trusted] is read once here, since a list asks it of every row. */
    data class Neighbour(val number: Double, val name: String, val ownerId: Long) {
        val trusted: Boolean = numberIsTrustworthy(name) && isRecognizedChapterNumber(number)
    }

    /**
     * The whole numbers each owner's chapters carry, taken from every chapter of the series, hidden ones
     * and the ones a filter drops included, so a number the series has elsewhere is never counted missing.
     * Every recognised number counts, believed or not: that can only take a marker away.
     */
    class Present private constructor(private val byOwner: Map<Long, IntArray>) {

        /** How many whole numbers strictly between [lo] and [hi] [owner] has no chapter for. */
        fun absentBetween(owner: Long, lo: Int, hi: Int): Int {
            val span = hi - lo - 1
            if (span <= 0) return 0
            val numbers = byOwner[owner] ?: return span
            return span - (firstAtLeast(numbers, hi) - firstAtLeast(numbers, lo + 1))
        }

        // A screen state carries this, so two equal lists must compare equal for it to settle.
        override fun equals(other: Any?): Boolean = other is Present &&
            byOwner.keys == other.byOwner.keys &&
            byOwner.all { (owner, numbers) -> numbers.contentEquals(other.byOwner.getValue(owner)) }

        override fun hashCode(): Int =
            byOwner.entries.sumOf { (owner, numbers) -> owner.hashCode() xor numbers.contentHashCode() }

        companion object {
            /** Nothing known to be present, which counts every number between two neighbours. */
            val NONE = Present(emptyMap())

            fun <T> of(chapters: Iterable<T>, ownerOf: (T) -> Long, numberOf: (T) -> Double): Present =
                Present(
                    chapters.filter { isRecognizedChapterNumber(numberOf(it)) }
                        .groupBy(ownerOf) { floor(numberOf(it)).toInt() }
                        .mapValues { (_, numbers) -> numbers.toSortedSet().toIntArray() },
                )

            private fun firstAtLeast(sorted: IntArray, value: Int): Int {
                var low = 0
                var high = sorted.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (sorted[mid] < value) low = mid + 1 else high = mid
                }
                return low
            }
        }
    }

    /** The whole numbers strictly between [lo] and [hi] of [owner] that a gap would count, before [Present]. */
    private data class Span(val owner: Long, val lo: Int, val hi: Int)

    private fun spanOf(higher: Neighbour?, lower: Neighbour?): Span? {
        if (higher == null || !higher.trusted) return null
        // The list edge: everything below the last chapter's number is missing.
        if (lower == null) return Span(higher.ownerId, 0, floor(higher.number).toInt())
        if (!lower.trusted) return null
        // Two sources of one entry count differently, so the difference measures nothing.
        if (higher.ownerId != lower.ownerId) return null
        // A pair the list order puts the wrong way round spans nothing rather than a negative something.
        return Span(higher.ownerId, floor(lower.number).toInt(), floor(higher.number).toInt())
    }

    private fun Present.count(span: Span?): Int = span?.let { absentBetween(it.owner, it.lo, it.hi) } ?: 0

    fun between(higher: Neighbour?, lower: Neighbour?, present: Present): Int = present.count(spanOf(higher, lower))

    /**
     * The count at a reader's boundary between two chapters. There a missing neighbour is the start or
     * end of what can be read, which the marker names itself, not chapters missing below the list.
     */
    fun atSeam(higher: Neighbour?, lower: Neighbour?, present: Present): Int =
        if (higher == null || lower == null) 0 else between(higher, lower, present)

    /**
     * [rows] as displayed with a marker wherever one is due, [before] above [after], null past either
     * end. The sort decides which side is the higher chapter. A hidden row, shown only while the user
     * reveals them, is never a side: the marker spans the shown rows around it and sits directly above
     * the one it leads into, so revealing hidden rows never changes a count.
     */
    fun <T, R> withMarkers(
        rows: List<T>,
        neighbourOf: (T) -> Neighbour,
        isHidden: (T) -> Boolean,
        present: Present,
        descending: Boolean,
        row: (T) -> R,
        marker: (before: T?, after: T?, count: Int) -> R,
    ): List<R> {
        val out = ArrayList<R>(rows.size + 1)
        walk(rows, neighbourOf, isHidden, descending, onRow = { out += row(it) }) { before, after, span ->
            present.count(span).takeIf { it > 0 }?.let { out += marker(before, after, it) }
        }
        return out
    }

    /**
     * The header's count: every number the markers of [rows] cover, each owner's number once, so two
     * markers over the same stretch of a list the source orders oddly are not added twice.
     */
    fun <T> total(
        rows: List<T>,
        neighbourOf: (T) -> Neighbour,
        isHidden: (T) -> Boolean,
        present: Present,
        descending: Boolean,
    ): Int {
        val spans = mutableListOf<Span>()
        walk(rows, neighbourOf, isHidden, descending, onRow = {}) { _, _, span ->
            if (span != null && span.hi - span.lo > 1) spans += span
        }
        return spans.groupBy { it.owner }.entries.sumOf { (owner, own) ->
            var counted = 0
            var start = Int.MIN_VALUE
            var end = Int.MIN_VALUE
            for (span in own.sortedBy { it.lo }) {
                if (span.lo < end) {
                    end = maxOf(end, span.hi)
                } else {
                    if (end > start) counted += present.absentBetween(owner, start, end)
                    start = span.lo
                    end = span.hi
                }
            }
            if (end > start) counted += present.absentBetween(owner, start, end)
            counted
        }
    }

    /** Hands each displayed row to [onRow] and each place a marker could sit to [onGap], in order. */
    private inline fun <T> walk(
        rows: List<T>,
        neighbourOf: (T) -> Neighbour,
        isHidden: (T) -> Boolean,
        descending: Boolean,
        onRow: (T) -> Unit,
        onGap: (before: T?, after: T?, span: Span?) -> Unit,
    ) {
        if (rows.isEmpty()) return
        var before: T? = null
        var beforeNeighbour: Neighbour? = null
        for (current in rows) {
            if (isHidden(current)) {
                onRow(current)
                continue
            }
            val currentNeighbour = neighbourOf(current)
            onGap(before, current, spanAcross(beforeNeighbour, currentNeighbour, descending))
            onRow(current)
            before = current
            beforeNeighbour = currentNeighbour
        }
        onGap(before, null, spanAcross(beforeNeighbour, null, descending))
    }

    private fun spanAcross(before: Neighbour?, after: Neighbour?, descending: Boolean): Span? =
        if (descending) spanOf(before, after) else spanOf(after, before)

    /**
     * Whether the recognized number can be believed, which it can only be when the name labels the
     * chapter with a plain number. A volume extra or epilogue has no chapter number of its own, but
     * its name still hands the recognizer a digit: "Chapter v11ex2: Vol 11 Extra 2" reads as chapter
     * 2, which under chapter 483 claimed 480 were missing.
     */
    private fun numberIsTrustworthy(name: String): Boolean {
        val tokens = token.findAll(name.lowercase()).map { it.value }.toList()
        // A leading volume is not the chapter: "Vol.1 Ch.14" is labelled from its third token.
        val volumeFirst = tokens.firstOrNull() in volumeWords && tokens.getOrNull(1)?.let(plainNumber::matches) == true
        val start = if (volumeFirst) 2 else 0
        val at = if (tokens.getOrNull(start) in labelWords) start + 1 else start
        val candidate = tokens.getOrNull(at) ?: return false
        if (!plainNumber.matches(candidate)) return false
        // Two numbers in the label and there is no telling which is the chapter: "Chapter 523 - 517"
        // is a site's own index beside the real number, and the recognizer takes the first.
        return tokens.getOrNull(at + 1)?.let { !plainNumber.matches(it) } ?: true
    }

    private val labelWords = setOf("chapter", "ch", "chap", "episode", "ep")
    private val volumeWords = setOf("volume", "vol")
    private val plainNumber = Regex("""^[0-9]+(\.[0-9]+)?$""")

    /**
     * A run of letters and digits, with a period kept only between two digits: "Chapter 5.5" is one
     * number, while "Ch.5" and "Chapter 5." lose the period. Letters glued to digits stay one token,
     * so "v11ex2" is never read as a number.
     */
    private val token = Regex("""(?:[a-z0-9]|(?<=[0-9])\.(?=[0-9]))+""")
}

/** A merged list's neighbours can come from different sources, so the owning manga travels with the
 *  number the gap is computed from. */
fun Chapter.toGapNeighbour() = ChapterGap.Neighbour(chapterNumber, name, mangaId)

/** The numbers a manga's gaps are counted against: every chapter of [this], as [ChapterGap.Present] asks. */
fun Iterable<Chapter>.gapPresent(): ChapterGap.Present =
    ChapterGap.Present.of(this, { it.mangaId }, { it.chapterNumber })
