package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The gap rule, pinned once for both content types. */
class ChapterGapTest {

    private fun at(number: Double, name: String = "Chapter $number", owner: Long = 1L) =
        ChapterGap.Neighbour(number, name, owner)

    private val none = ChapterGap.Present.NONE

    private fun presentOf(vararg rows: ChapterGap.Neighbour) =
        ChapterGap.Present.of(rows.asList(), { it.ownerId }, { it.number })

    /** A list in reading order, oldest first, with each row's number and name as the source gave them. */
    private fun ascending(vararg rows: Pair<Double, String>) = rows.map { (number, name) -> at(number, name) }

    private fun markers(rows: List<ChapterGap.Neighbour>) =
        ChapterGap.withMarkers(
            rows,
            neighbourOf = { it },
            isHidden = { false },
            present = ChapterGap.Present.of(rows, { it.ownerId }, { it.number }),
            descending = false,
            row = { "${it.number}" },
        ) { _, _, count -> "missing $count" }
            .filter { it.startsWith("missing") }

    private fun header(rows: List<ChapterGap.Neighbour>) =
        ChapterGap.total(rows, { it }, { false }, ChapterGap.Present.of(rows, { it.ownerId }, { it.number }), false)

    @Test
    @DisplayName("consecutive chapters are missing nothing")
    fun consecutiveIsZero() {
        ChapterGap.between(at(5.0), at(4.0), none) shouldBe 0
    }

    @Test
    @DisplayName("a skipped chapter is counted")
    fun oneSkippedIsOne() {
        ChapterGap.between(at(5.0), at(3.0), none) shouldBe 1
    }

    @Test
    @DisplayName("a decimal chapter does not count as a gap")
    fun decimalsAreFloored() {
        ChapterGap.between(at(5.5), at(5.0), none) shouldBe 0
    }

    @Test
    @DisplayName("no neighbour below means everything under it is missing")
    fun leadingEdgeCountsDownToOne() {
        ChapterGap.between(at(4.0), null, none) shouldBe 3
    }

    @Test
    @DisplayName("two sources of one entry are not compared")
    fun crossSourcePairIsDeclined() {
        // The same chapter is numbered differently by each source, so the difference measures nothing.
        ChapterGap.between(at(526.0, owner = 1L), at(522.0, owner = 2L), none) shouldBe 0
    }

    @Test
    @DisplayName("a volume extra's recognized number is not believed")
    fun volumeExtraIsDeclined() {
        val extra = at(2.0, name = "Chapter v11ex2: Vol 11 Extra 2: A Brief Repit")

        ChapterGap.between(at(483.0, name = "Chapter 483: What Was Lost"), extra, none) shouldBe 0
    }

    @Test
    @DisplayName("an epilogue's recognized number is not believed either")
    fun epilogueIsDeclined() {
        val epilogue = at(1.0, name = "Chapter epl1: Vol 11 Epilogue", owner = 1L)

        ChapterGap.between(at(483.0, name = "Chapter 483: What Was Lost"), epilogue, none) shouldBe 0
    }

    @Test
    @DisplayName("a label that is not a plain number is not believed")
    fun unparseableLabelIsDeclined() {
        ChapterGap.between(at(9.0, name = "Chapter v2s3: Something"), at(3.0), none) shouldBe 0
    }

    @Test
    @DisplayName("a title carrying two numbers is not believed")
    fun dualNumberedTitleIsDeclined() {
        // "siteIndex - realNumber" naming: the recognizer takes the first, which is the site's own
        // index, so comparing it with a plainly numbered neighbour invents a gap.
        val higher = at(523.0, name = "Chapter 523 - 517")
        val lower = at(516.0, name = "Chapter 516: Ever His Humble Servant")

        ChapterGap.between(higher, lower, none) shouldBe 0
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(
        "'Ch. 14', 'Ch. 10'",
        "'Ch.14', 'Ch.10'",
        "'Chapter 14.', 'Chapter 10.'",
        "'Vol.1 Ch.14', 'Vol.1 Ch.10'",
        "'Vol 2 Chapter 14', 'Volume 2 Chapter 10'",
    )
    @DisplayName("a label written with a period or behind a volume is believed")
    fun punctuatedLabelIsBelieved(higherName: String, lowerName: String) {
        ChapterGap.between(at(14.0, name = higherName), at(10.0, name = lowerName), none) shouldBe 3
    }

    @Test
    @DisplayName("a volume extra behind a volume number is still not believed")
    fun volumePrefixedExtraIsDeclined() {
        ChapterGap.between(at(483.0, name = "Chapter 483"), at(2.0, name = "Vol 11 Extra 2"), none) shouldBe 0
    }

    @Test
    @DisplayName("a decimal chapter reads as one number, not two")
    fun decimalIsOneNumber() {
        ChapterGap.between(at(7.0, name = "Chapter 7"), at(5.5, name = "Chapter 5.5"), none) shouldBe 1
    }

    @Test
    @DisplayName("an unrecognized number is not a gap")
    fun negativeNumberIsDeclined() {
        ChapterGap.between(at(5.0), at(-1.0, name = "Chapter -1.0"), none) shouldBe 0
    }

    @Test
    @DisplayName("at a reader's boundary, nothing below the chapter is not a gap")
    fun seamWithoutLowerNeighbourIsZero() {
        ChapterGap.atSeam(at(4.0), null, none) shouldBe 0
    }

    @Test
    @DisplayName("at a reader's boundary a skipped chapter is still counted")
    fun seamCountsASkip() {
        ChapterGap.atSeam(at(5.0), at(3.0), none) shouldBe 1
    }

    @Test
    @DisplayName("at a reader's boundary a number the list carries elsewhere is not missing")
    fun seamSkipsANumberTheListHas() {
        // A swapped pair read in source order: 3132 follows 3130, and 3131 comes after it.
        val present = presentOf(at(3130.0), at(3131.0), at(3132.0))

        ChapterGap.atSeam(at(3132.0), at(3130.0), present) shouldBe 0
    }

    @Test
    @DisplayName("a repost of an old chapter at the end of the list leaves no gap after it")
    fun repostIsNotAGap() {
        // Complete Martial Arts Attributes: the source re-listed chapter 4819 between 4832 and 4833.
        val rows = (1..4832).map { at(it.toDouble()) } + ascending(
            4819.0 to "Chapter 4819: Plan Disrupted! Devil Titan Xue",
            4833.0 to "Chapter 4833 Seventh-Level Devil Flame Will!",
        )

        markers(rows) shouldBe emptyList()
    }

    @Test
    @DisplayName("chapters the source lists in swapped pairs are missing nothing")
    fun swappedPairsAreNotGaps() {
        // Divine Emperor of Death, in source order.
        val rows = (1..3129).map { at(it.toDouble()) } + ascending(
            3130.0 to "Chapter 3130 Avatar Seared?",
            3132.0 to "Chapter 3132 Subduing Apocalyptic Flames",
            3131.0 to "Chapter 3131 Trying To Tame",
            3133.0 to "Chapter 3133 Melting Ascendance",
        )

        markers(rows) shouldBe emptyList()
    }

    @Test
    @DisplayName("a run of early chapters listed again before a late one leaves no gap")
    fun duplicateRunIsNotAGap() {
        // The Max Level Hero Has Returned!: chapters 7 to 11 listed again just before 158.
        val rows = (1..157).map { at(it.toDouble()) } + (7..11).map { at(it.toDouble()) } + at(158.0)

        markers(rows) shouldBe emptyList()
    }

    @Test
    @DisplayName("a stray early number counts only the chapters truly absent after it")
    fun strayNumberCountsOnlyTheAbsent() {
        // I Save The World In A Doomsday Text Game: "Chapter (2)" sits before 397, and only 396 is gone.
        val rows = (3..395).map { at(it.toDouble()) } + ascending(
            1.0 to "Chapter 1",
            2.0 to "Chapter (2)",
            397.0 to "Chapter 397: An Old Friend",
        )

        markers(rows) shouldBe listOf("missing 1")
    }

    @Test
    @DisplayName("a long gap partly filled elsewhere in the list counts only the absent numbers")
    fun partlyFilledGapCountsTheRest() {
        val rows = (1..1134).map { at(it.toDouble()) } + at(1335.0) + (1136..1200).map { at(it.toDouble()) }

        markers(rows).first() shouldBe "missing 135"
    }

    @Test
    @DisplayName("the header counts a number two markers both cover once")
    fun headerCountsEachNumberOnce() {
        // 5, 20, 8, 12 in source order: 5 to 20 covers 9 to 11, and so does 8 to 12.
        val rows = listOf(at(1.0), at(2.0), at(3.0), at(4.0), at(5.0), at(20.0), at(8.0), at(12.0)) +
            listOf(6.0, 7.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0).map { at(it) }

        header(rows) shouldBe 3
    }

    @Test
    @DisplayName("a revealed hidden row is never a side of a marker")
    fun revealedHiddenRowIsNotANeighbour() {
        // A misnumbered chapter the user hid, shown again: it must not open a gap of hundreds.
        val stray = at(500.0)
        val rows = (1..10).map { at(it.toDouble()) } + stray + at(11.0)

        ChapterGap.withMarkers(
            rows,
            neighbourOf = { it },
            isHidden = { it == stray },
            present = presentOf(*rows.toTypedArray()),
            descending = false,
            row = { "${it.number}" },
        ) { _, _, count -> "missing $count" }.takeLast(3) shouldBe listOf("10.0", "500.0", "11.0")
    }

    @ParameterizedTest(name = "before {0}, after {1}, descending {2}: {3}")
    @CsvSource(
        "4.0, 6.0, false, 1",
        "6.0, 4.0, true, 1",
        "6.0, 4.0, false, 0",
        ", 4.0, false, 3",
        "4.0, , true, 3",
        "4.0, , false, 0",
        ", 4.0, true, 0",
    )
    @DisplayName("a marker between two displayed rows counts from whichever the sort puts higher")
    fun markerFollowsTheSort(before: Double?, after: Double?, descending: Boolean, expected: Int) {
        val rows = listOfNotNull(before, after).map { at(it) }

        ChapterGap.withMarkers(rows, { it }, { false }, none, descending, { 0 }) { b, a, count ->
            if (b?.number == before && a?.number == after) count else 0
        }.sum() shouldBe expected
    }

    /** Page 4 of a paged list, chapters 301 to 390 with 350 absent, in display order. */
    private fun page(descending: Boolean): List<ChapterGap.Neighbour> {
        val rows = (301..390).filter { it != 350 }.map { at(it.toDouble()) }
        return if (descending) rows.reversed() else rows
    }

    @ParameterizedTest(name = "descending {0}")
    @CsvSource("false", "true")
    @DisplayName("a page of a paged list marks the gaps inside it, not the earlier pages")
    fun pagedMarkersSkipThePageEnds(descending: Boolean) {
        val rows = page(descending)

        ChapterGap.withMarkers(
            rows,
            neighbourOf = { it },
            isHidden = { false },
            present = presentOf(*rows.toTypedArray()),
            descending = descending,
            row = { 0 },
            paged = true,
        ) { _, _, count -> count }.filter { it > 0 } shouldBe listOf(1)
    }

    @ParameterizedTest(name = "descending {0}")
    @CsvSource("false", "true")
    @DisplayName("a page of a paged list counts the gaps inside it, not the earlier pages")
    fun pagedTotalSkipsThePageEnds(descending: Boolean) {
        val rows = page(descending)

        ChapterGap.total(rows, { it }, { false }, presentOf(*rows.toTypedArray()), descending, paged = true) shouldBe 1
    }

    @ParameterizedTest(name = "descending {0}")
    @CsvSource("false", "true")
    @DisplayName("the header total adds the inline markers and the oldest end in either order")
    fun totalMatchesTheMarkers(descending: Boolean) {
        val ascending = listOf(at(3.0), at(4.0), at(7.0))
        val displayed = if (descending) ascending.reversed() else ascending

        ChapterGap.total(displayed, { it }, { false }, presentOf(*ascending.toTypedArray()), descending) shouldBe 4
    }
}
