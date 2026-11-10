package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Which same-numbered chapters of one entry are copies of one chapter, over names taken from real
 * libraries and from unseensnick/Reikai#85, numbered by the real recognizer as a sync numbers them.
 */
class ChapterCopiesTest {

    data class Case(val first: String, val second: String, val origins: Pair<String?, String?>, val copies: Boolean) {
        override fun toString() = "$first / $second${if (origins.first != origins.second) " (two scanlators)" else ""}"
    }

    private data class Ch(val id: Long, val name: String, val origin: String?, val number: Double = -1.0)

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `same-numbered chapters are copies only when the names say they are one chapter`(case: Case) {
        val numbered = recognizeChapterNumbers(
            "Series",
            listOf(Ch(1, case.first, case.origins.first), Ch(2, case.second, case.origins.second)),
            nameOf = { it.name },
            numberOf = { it.number },
            withNumber = { chapter, number -> chapter.copy(number = number) },
        )
        val sets = numbered.chapterCopySets({ it.number }, { it.name }, { it.origin }, { 1L })

        // The shared number is part of the expectation, so a case cannot pass by the recognizer splitting it.
        (numbered.map { it.number }.distinct().size to (sets.size == 1)) shouldBe (1 to case.copies)
    }

    /** A local volume folder named by a bare number leads its chapters' names, which restart in each volume. */
    @Test
    fun `chapters of two volumes named by bare numbers are apart`() {
        val chapters = listOf(Ch(1, "01 - Ch 001", null, 1.0), Ch(2, "03 - Ch 001", null, 1.0))

        chapters.chapterCopySets({ it.number }, { it.name }, { it.origin }, { 1L }).size shouldBe 2
    }

    /** Folders `01/` and `02/` each holding `001.cbz`, numbered from the chapter name alone. */
    @Test
    fun `bare-numbered chapters of two bare-numbered volumes are apart`() {
        val chapters = listOf(Ch(1, "01 - 001", null, 1.0), Ch(2, "02 - 001", null, 1.0))

        chapters.chapterCopySets({ it.number }, { it.name }, { it.origin }, { 1L }).size shouldBe 2
    }

    @Test
    fun `one bare-numbered chapter of one volume from two scanlators is a copy`() {
        val chapters = listOf(Ch(1, "01 - 001", "A", 1.0), Ch(2, "01 - 001", "B", 1.0))

        chapters.chapterCopySets({ it.number }, { it.name }, { it.origin }, { 1L }).size shouldBe 1
    }

    @Test
    fun `each copy reports the size of its set, and a chapter with none is absent`() {
        val chapters = listOf(Ch(1, "Chapter 1", "A", 1.0), Ch(2, "Chapter 1", "B", 1.0), Ch(3, "Chapter 2", "A", 2.0))

        chapters.duplicateCopyCounts({ it.number }, { it.name }, { it.origin }, { 1L }, { it.id }, { null }) shouldBe
            mapOf(1L to 2, 2L to 2)
    }

    companion object {
        private fun copies(first: String, second: String, origins: Pair<String?, String?> = null to null) =
            Case(first, second, origins, copies = true)

        private fun apart(first: String, second: String, origins: Pair<String?, String?> = null to null) =
            Case(first, second, origins, copies = false)

        @JvmStatic
        fun cases() = listOf(
            copies("Chapter 2", "Chapter 2: New World"),
            copies("2", "Chapter 2: New World"),
            copies("CH.705 Alaric's First Day in the Dungeon", "705 CH.705"),
            copies("Chapter 527 - 526", "Chapter 527"),
            copies("Chapter 120.0", "Chapter 120"),
            copies("Chaprer 19", "Chapter 19"),
            copies("Chapter 3: The Duel", "Chapter 3: Duel"),
            copies("1", "Chapter 1: Prologue"),
            copies("Ch. 26 - Last Game", "Ch. 26 - (Stick)", "unofficial" to "official"),
            copies("Vol.2 Ch.4", "Ch.4"),
            copies("第12章 回家", "第12章：回家"),
            copies("Глава 12", "Глава 12: Возвращение"),
            apart("Interlude 1", "Chapter 1"),
            apart("Interlude 1", "Chapter 1", "alpha" to "beta"),
            apart("Volume 1: The Trap 2", "Chapter 2"),
            apart("Side Story 7", "Episode 7"),
            apart("Notice.32", "Chapter 32"),
            apart("Chapter 1 - Side Story", "Chapter 1"),
            apart("Chapter 39: Saint Paul (1)", "Chapter 39: Saint Paul (2)"),
            apart("Chapter 92: Collapse of Xyrus", "Chapter 92: Bird's Cage"),
            apart("Chapter 16-20", "Chapter 16.2"),
            apart("Vol.1 Chapter 1", "Vol.2 Chapter 1"),
            apart("第12章", "番外12"),
            apart("Ch. 1 - Double Dungeon", "Special Episode 1", "official" to "unofficial"),
            apart("Ch. 1", "Special Episode 1", "unofficial" to "official"),
            apart("Hoofdstuk 4", "Bonushoofdstuk 4"),
        )
    }
}
