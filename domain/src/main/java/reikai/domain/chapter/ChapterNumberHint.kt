package reikai.domain.chapter

import kotlin.math.abs
import kotlin.math.floor

/**
 * Chapters whose number is far out of line with their neighbours in the source's own list, for manga and
 * novels alike. Only ever a marker with a suggestion: nothing changes a number until the user saves one.
 * Thresholds and the side-content skip are measured against the owner's library; see
 * docs/dev/subsystems/details.md.
 */
object ChapterNumberHint {

    /** [suggestion] is the whole number that fits between the row's neighbours, null when none does. */
    data class Hint(val suggestion: Double?)

    /**
     * The last answer for one screen's rows. A download tick re-renders the chapter list over the same rows
     * and hidden set, so it reuses the answer instead of walking every chapter again.
     */
    class Memo<T> {
        private var last: Triple<List<T>, Set<String>, Map<Long, Hint>>? = null

        @Synchronized
        fun get(rows: List<T>, hidden: Set<String>, compute: () -> Map<Long, Hint>): Map<Long, Hint> {
            last?.let { (lastRows, lastHidden, hints) -> if (lastRows == rows && lastHidden == hidden) return hints }
            return compute().also { last = Triple(rows, hidden, it) }
        }
    }

    /**
     * Hints for [rows] by [id]. Each owner is judged on its own list in [sourceOrder], either direction: a
     * merged list restamps the order and keeps one copy per chapter, so it cannot be judged as one. A row
     * the user hid ([isHidden]) is left out, so it is never marked and never shapes a drawn row's run.
     * [dateUpload] is epoch millis, 0 when the source gives none.
     */
    fun <T> forOwners(
        rows: Iterable<T>,
        id: (T) -> Long,
        owner: (T) -> Long,
        sourceOrder: (T) -> Long,
        number: (T) -> Double,
        name: (T) -> String,
        dateUpload: (T) -> Long,
        isHidden: (T) -> Boolean,
    ): Map<Long, Hint> {
        val hints = HashMap<Long, Hint>()
        for (own in rows.groupBy(owner).values) {
            val ordered = own.filterNot(isHidden).sortedBy(sourceOrder)
            val counted = countedRows(ordered, name).filter { isRecognizedChapterNumber(number(it)) }
            outOfLine(counted, number) { row, before, after, hint ->
                if (!isMisplacedCopy(row, before, after, counted, number, dateUpload)) hints[id(row)] = hint
            }
        }
        return hints
    }

    private inline fun <T> outOfLine(
        rows: List<T>,
        number: (T) -> Double,
        mark: (row: T, before: T, after: T, Hint) -> Unit,
    ) {
        val runs = mutableListOf<MutableList<T>>()
        for (row in rows) {
            val run = runs.lastOrNull()
            if (run != null &&
                abs(number(row) - number(run.last())) <= RUN_BREAK
            ) {
                run += row
            } else {
                runs += mutableListOf(row)
            }
        }
        for (i in 1 until runs.lastIndex) {
            val run = runs[i]
            if (run.size > MAX_STRAY_RUN) continue
            val before = runs[i - 1].last()
            val after = runs[i + 1].first()
            val low = minOf(number(before), number(after))
            val high = maxOf(number(before), number(after))
            if (high - low > RUN_BREAK) continue
            val hint = Hint((floor(low) + 1).takeIf { it < high })
            run.forEach { mark(it, before, after, hint) }
        }
    }

    /**
     * A copy of a chapter uploaded beside its same-numbered twin and far from the rows the source lists it
     * between: its number is right and only its place is wrong, which no renumber fixes. Matching the twin
     * rather than any nearby number keeps a stray dated between its neighbouring numbers but not beside its
     * twin marked. An unknown date never decides.
     */
    private fun <T> isMisplacedCopy(
        row: T,
        before: T,
        after: T,
        own: List<T>,
        number: (T) -> Double,
        date: (T) -> Long,
    ): Boolean {
        val at = date(row)
        if (date(before) <= 0 || date(after) <= 0) return false
        if (abs(at - date(before)) <= PLACE_GAP || abs(at - date(after)) <= PLACE_GAP) return false
        return own.any { it !== row && number(it) == number(row) && date(it) > 0 && abs(date(it) - at) <= COPY_GAP }
    }

    /**
     * [ordered] without side content, whose own numbering never lines up with the main story's: a row
     * its name labels as such, and a row with no volume label that a volume-labelled list interleaves
     * (a bonus part numbered by its volume, "Chapter 3.1" after "Vol.3 Chapter 15").
     */
    private fun <T> countedRows(ordered: List<T>, name: (T) -> String): List<T> {
        val labelled = ordered.map { volumeLead.containsMatchIn(name(it)) }
        val first = labelled.indexOf(true)
        val last = labelled.lastIndexOf(true)
        return ordered.filterIndexed { i, row ->
            !isSideContent(name(row)) && (labelled[i] || i !in first..last)
        }
    }

    private fun isSideContent(name: String): Boolean {
        val words = word.findAll(name.lowercase()).map { it.value }.toList()
        return words.any { it in sideWords } ||
            words.zipWithNext().any { (a, b) -> a == "side" && (b == "story" || b == "stories") }
    }

    private val sideWords = setOf(
        "extra", "extras", "special", "specials", "omake", "epilogue", "prologue", "bonus", "afterword",
        "illustration", "illustrations", "sidestory",
    )
    private val word = Regex("[a-z]+")
    private val volumeLead = Regex("""^\s*vol(?:ume)?\.?\s*\d""", RegexOption.IGNORE_CASE)

    // A looser break chains strays into real runs, and without the run cap a source's own side numbering
    // is marked wholesale.
    private const val RUN_BREAK = 10.0
    private const val MAX_STRAY_RUN = 5

    // Measured on the owner's library, the gaps sit far either side: the one misplaced copy is hours from
    // its twin and years from its place, every other dated stray is months from any twin or days from its place.
    private const val DAY_MILLIS = 86_400_000L
    private const val COPY_GAP = 2 * DAY_MILLIS
    private const val PLACE_GAP = 30 * DAY_MILLIS
}
