package reikai.domain.chapter

import kotlin.math.abs
import kotlin.math.floor

/**
 * Chapters whose number is far out of line with their neighbours in the source's own list, for manga and
 * novels alike. Only ever a marker with a suggestion: nothing changes a number until the user saves one.
 * Thresholds and the side-content skip are measured against the owner's library; see
 * docs/dev/plans/chapter-number-override.md.
 */
object ChapterNumberHint {

    /** [suggestion] is the whole number that fits between the row's neighbours, null when none does. */
    data class Hint(val suggestion: Double?)

    /**
     * Hints for [rows] by [id]. Each owner is judged on its own list in [sourceOrder], either direction: a
     * merged list restamps the order and keeps one copy per chapter, so it cannot be judged as one. A row
     * the user hid ([isHidden]) is left out, so it is never marked and never shapes a drawn row's run.
     */
    fun <T> forOwners(
        rows: Iterable<T>,
        id: (T) -> Long,
        owner: (T) -> Long,
        sourceOrder: (T) -> Long,
        number: (T) -> Double,
        name: (T) -> String,
        isHidden: (T) -> Boolean,
    ): Map<Long, Hint> {
        val hints = HashMap<Long, Hint>()
        for (own in rows.groupBy(owner).values) {
            val ordered = own.filterNot(isHidden).sortedBy(sourceOrder)
            val counted = countedRows(ordered, name).filter { isRecognizedChapterNumber(number(it)) }
            outOfLine(counted, number) { row, hint -> hints[id(row)] = hint }
        }
        return hints
    }

    private inline fun <T> outOfLine(rows: List<T>, number: (T) -> Double, mark: (T, Hint) -> Unit) {
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
            val before = number(runs[i - 1].last())
            val after = number(runs[i + 1].first())
            if (abs(after - before) > RUN_BREAK) continue
            val next = floor(minOf(before, after)) + 1
            val hint = Hint(next.takeIf { it < maxOf(before, after) })
            run.forEach { mark(it, hint) }
        }
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
}
