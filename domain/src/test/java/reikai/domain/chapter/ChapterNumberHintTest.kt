package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Fixtures are real rows from the owner's library, in the source's own order. */
class ChapterNumberHintTest {

    private data class Row(
        val id: Long,
        val owner: Long,
        val order: Long,
        val number: Double,
        val name: String,
        val hidden: Boolean = false,
        val date: Long = 0L,
    )

    /** One owner's rows in source order, ids counting from 0. */
    private fun list(vararg rows: Pair<Double, String>, owner: Long = 1L) =
        rows.mapIndexed { i, (number, name) -> Row(i.toLong(), owner, i.toLong(), number, name) }

    private fun numbered(vararg numbers: Double) = list(*numbers.map { it to "Chapter $it" }.toTypedArray())

    private fun hints(rows: List<Row>) = ChapterNumberHint.forOwners(
        rows,
        id = { it.id },
        owner = { it.owner },
        sourceOrder = { it.order },
        number = { it.number },
        name = { it.name },
        dateUpload = { it.date },
        isHidden = { it.hidden },
    )

    private fun hint(suggestion: Double?) = ChapterNumberHint.Hint(suggestion)

    /**
     * The Legendary Mechanic's shape: chapters 179 down to 1, one a week, and a copy of chapter 177 that the
     * site lists between chapters 3 and 2. Ids are the source order: the twin is 2, the copy 177.
     */
    private fun misplacedCopy(copyDate: Long = START + 177 * WEEK + 2 * HOUR): List<Row> {
        val rows = numbered(*(179 downTo 1).map { it.toDouble() }.toDoubleArray())
            .map { it.copy(date = START + it.number.toLong() * WEEK) }
        val copy = Row(0L, 1L, 0L, 177.0, "Chapte 177", date = copyDate)
        return (rows.take(177) + copy + rows.drop(177)).mapIndexed { i, row ->
            row.copy(id = i.toLong(), order = i.toLong())
        }
    }

    private fun List<Row>.undated(vararg ids: Long) = map { if (it.id in ids) it.copy(date = 0L) else it }

    @Test
    fun `a copy listed out of place beside its same-day twin is not marked`() {
        hints(misplacedCopy()) shouldBe emptyMap()
    }

    @Test
    fun `a copy dated beside the chapters around its number but not its twin stays marked`() {
        // Martial Peak's "Chapter497": a day from 496 and 498, two years from the "Chapter 497" the list also has.
        hints(misplacedCopy(copyDate = START + 176 * WEEK + DAY)) shouldBe mapOf(177L to hint(null))
    }

    @Test
    fun `a misnumbered row in a list uploaded all at once stays marked`() {
        // The real chapter 1335 sits at the end, past a run too long to be judged a stray itself.
        val rows = numbered(1133.0, 1134.0, 1335.0, 1136.0, 1137.0, 1138.0, 1139.0, 1140.0, 1141.0, 1335.0)
            .map { it.copy(date = START) }
        hints(rows) shouldBe mapOf(2L to hint(1135.0))
    }

    @Test
    fun `an undated copy and twin leave the copy marked`() {
        hints(misplacedCopy().undated(2L, 177L)) shouldBe mapOf(177L to hint(null))
    }

    @Test
    fun `an undated neighbour leaves a misplaced copy marked`() {
        hints(misplacedCopy().undated(176L)) shouldBe mapOf(177L to hint(null))
    }

    @Test
    fun `two unnumbered parts between 395-2 and 397 are marked and offered 396`() {
        val rows = list(
            394.2 to "Chapter 394-2: The Vengeance Goddess's Reward, Meeting the Master (2)",
            395.1 to "Chapter 395-1: Astonishing Battle Record, Authority of the Immortal Realm (1)",
            395.2 to "Chapter 395-2: Astonishing Battle Record, Authority of the Immortal Realm (2)",
            1.0 to "Chapter (1)",
            2.0 to "Chapter (2)",
            397.0 to "Chapter 397: An Old Friend from the World of Omniscience, Polar Star Society!",
            398.1 to "Chapter 398-1: Starlight Priest, Time Prison (1)",
        )
        hints(rows) shouldBe mapOf(3L to hint(396.0), 4L to hint(396.0))
    }

    @Test
    fun `a list the source orders newest first gets the same suggestion`() {
        val rows = list(398.1 to "a", 397.0 to "b", 1.0 to "Chapter (1)", 395.2 to "c", 395.1 to "d")
        hints(rows) shouldBe mapOf(2L to hint(396.0))
    }

    @Test
    fun `a misplaced chapter is offered the number its neighbours leave free`() {
        val rows = list(
            1133.0 to "Chapter 1133",
            1134.0 to "Chapter 1134 Relia's Anger and Information",
            1335.0 to "Chapter 1335 Magma Sea",
            1136.0 to "apter 1136 Ruler of the Molten Depths",
            1137.0 to "Chapter 1137 Marcus' Bluff",
        )
        hints(rows) shouldBe mapOf(2L to hint(1135.0))
    }

    @Test
    fun `a stray between two consecutive chapters is marked with no suggestion`() {
        val rows = list(
            1658.0 to "Chapter 1658",
            1659.0 to "Chapter 1659",
            497.0 to "Chapter497",
            1660.0 to "Chapter 1660",
            1661.0 to "Chapter 1661",
        )
        hints(rows) shouldBe mapOf(2L to hint(null))
    }

    @Test
    fun `each owner is judged on its own list`() {
        // Pooled, the second source's 50s sit far from the first's 400s; each list alone is in line.
        val first = list(400.0 to "a", 401.0 to "b", 402.0 to "c", owner = 1L)
        val second = list(50.0 to "x", 51.0 to "y", 52.0 to "z", owner = 2L).map { it.copy(id = it.id + 10) }
        hints((first + second).sortedBy { it.number }) shouldBe emptyMap()
    }

    @Test
    fun `swapped neighbours are not marked`() {
        hints(numbered(3129.0, 3130.0, 3132.0, 3131.0, 3133.0, 3134.0)) shouldBe emptyMap()
    }

    @Test
    fun `a short run between two far-apart runs is a genuine jump`() {
        hints(numbered(18.0, 19.0, 20.0, 100.0, 101.0, 102.0, 200.0, 201.0)) shouldBe emptyMap()
    }

    @Test
    fun `six rows out of line are a numbering of their own`() {
        hints(numbered(395.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 396.0)) shouldBe emptyMap()
    }

    @Test
    fun `a stray the user hid is not marked`() {
        val rows = numbered(1658.0, 1659.0, 497.0, 1660.0, 1661.0).map { it.copy(hidden = it.number == 497.0) }
        hints(rows) shouldBe emptyMap()
    }

    @Test
    fun `a hidden row does not count toward a run of its own numbering`() {
        // Six out of line would be a numbering of their own; with one hidden, the five drawn are strays.
        val rows = numbered(395.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 396.0).map { it.copy(hidden = it.number == 6.0) }
        hints(rows) shouldBe (1L..5L).associateWith { hint(null) }
    }

    @Test
    fun `an unrecognised number neither splits a run nor is marked`() {
        hints(numbered(10.0, 11.0, -1.0, 12.0)) shouldBe emptyMap()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Mo Nian Side Story  1",
            "Side Story - Jasmine Wind-Borne Ep. 9",
            "Extra.134",
            "Special Episode 20",
            "Omake 2",
            "Chapter epl1: Vol 11 Epilogue",
            "Prologue 1",
            "Bonus 1",
            "Afterword 1",
            "Illustrations 1",
        ],
    )
    fun `a chapter its name labels as side content is never marked`(name: String) {
        val rows = list(1810.0 to "Chapter 1810", 1811.0 to "Chapter 1811", 1.0 to name, 1812.0 to "Chapter 1812")
        hints(rows) shouldBe emptyMap()
    }

    @Test
    fun `side content leaves the main run unbroken around it`() {
        val rows = list(
            9.0 to "Special Episode 9",
            179.1 to "Ch. 179.1 - Side Story 1",
            10.0 to "Special Episode 10",
            11.0 to "Special Episode 11",
        )
        hints(rows) shouldBe emptyMap()
    }

    @Test
    fun `bonus parts a volume-labelled list interleaves are never marked`() {
        val rows = list(
            15.5 to "Vol.3 Chapter 15.5",
            3.1 to "Chapter 3.1",
            3.2 to "Chapter 3.2",
            3.9 to "Chapter 3.9",
            16.0 to "Vol.4 Chapter 16",
            17.0 to "Vol.4 Chapter 17",
            18.0 to "Vol.4 Chapter 18",
            19.0 to "Vol.4 Chapter 19",
            4.1 to "Chapter 4.1",
            21.0 to "Vol.5 Chapter 21",
            22.0 to "Vol.5 Chapter 22",
            5.6 to "Chapter 5.6",
            23.0 to "Vol.5 Chapter 23",
        )
        hints(rows) shouldBe emptyMap()
    }

    @Test
    fun `rows past the last volume-labelled one still count`() {
        val rows = list(
            1.0 to "Vol.1 Chapter 1",
            2.0 to "Vol.1 Chapter 2",
            3.0 to "Chapter 3",
            300.0 to "Chapter 300",
            5.0 to "Chapter 5",
        )
        hints(rows) shouldBe mapOf(3L to hint(4.0))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
        const val START = 1_600_000_000_000L
    }
}
