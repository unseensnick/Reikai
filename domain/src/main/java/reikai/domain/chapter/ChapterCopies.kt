package reikai.domain.chapter

import tachiyomi.domain.chapter.service.ChapterRecognition
import kotlin.math.floor

/**
 * Splits [this] into sets of copies of one chapter, every chapter in exactly one set, in the order of
 * each set's first member. Copies share an entry ([ownerOf]) and a recognised number, write that number
 * as the chapter's own, name the same volume when both name one, and come from different scanlators or
 * carry matching titles. A shared number alone is not enough: "Interlude 1" is no copy of "Chapter 1".
 * The rule and the libraries it was measured on are in docs/dev/subsystems/reader.md, "Duplicate chapters".
 */
fun <T> List<T>.chapterCopySets(
    numberOf: (T) -> Double,
    nameOf: (T) -> String,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
): List<List<T>> {
    val sets = mutableListOf<List<T>>()
    groupBy { ownerOf(it) to numberOf(it) }.values.forEach { sameNumber ->
        val number = numberOf(sameNumber.first())
        if (sameNumber.size == 1 || !isRecognizedChapterNumber(number)) {
            sameNumber.forEach { sets += listOf(it) }
            return@forEach
        }
        val written = sameNumber.map { WrittenChapter(nameOf(it), number, originOf(it)) }
        val root = IntArray(sameNumber.size) { it }
        fun find(i: Int): Int = if (root[i] == i) i else find(root[i]).also { root[i] = it }
        for (i in sameNumber.indices) {
            for (j in i + 1 until sameNumber.size) {
                if (written[i].isCopyOf(written[j])) root[find(j)] = find(i)
            }
        }
        sameNumber.indices.groupBy(::find).values.forEach { members -> sets += members.map(sameNumber::get) }
    }
    val firstIndex = withIndex().associate { (index, chapter) -> chapter to index }
    return sets.sortedBy { set -> set.minOf { firstIndex.getValue(it) } }
}

/** What a chapter's name says about the number it is stored under, read once per same-numbered set. */
private class WrittenChapter(name: String, number: Double, origin: String?) {
    private val origin = origin.orEmpty().trim()
    private val volume: Double?
    private val numbered: Boolean
    private val words: List<String>
    private val marksExtra: Boolean

    init {
        val lower = name.lowercase()
        volume = ChapterRecognition.volume.find(lower)?.groupValues?.get(1)?.toDoubleOrNull()
        val text = ChapterRecognition.volume.replace(lower, " ")
        val whole = floor(number).toLong()
        // The stored number as the name writes it, decimals and a sub-chapter letter included.
        val written = Regex("""(?<![0-9])0*$whole(?:[.,][0-9]+)?(?:\.?[a-z](?![\p{L}]))?(?![0-9])""")
        val at = written.find(text)
        numbered = at != null && !isRange(text, at, whole) && isChapterPosition(text.substring(0, at.range.first))
        words = written.replace(text, " ").split(nonWord)
            .filter {
                it.isNotEmpty() && it !in markers && it !in stopWords && !isMarkerTypo(it) &&
                    !foreignWord.matches(it)
            }
        marksExtra = extraWord.containsMatchIn(text)
    }

    fun isCopyOf(other: WrittenChapter): Boolean {
        if (!numbered || !other.numbered) return false
        if (volume != null && other.volume != null && volume != other.volume) return false
        // Before the scanlator check: two groups each translating a side story is no reason to fold it.
        if (marksExtra != other.marksExtra) return false
        return origin != other.origin || titlesMatch(other)
    }

    // A bare "Chapter 2" matches "Chapter 2: New World"; words one title adds to the other's still match.
    private fun titlesMatch(other: WrittenChapter): Boolean {
        if (words.isEmpty() || other.words.isEmpty()) return true
        return words.toSet().containsAll(other.words) || other.words.toSet().containsAll(words)
    }

    private companion object {
        val nonWord = Regex("""[^\p{L}0-9]+""")
        val markers = setOf("c", "ch", "chap", "chapter", "ep", "episode")
        val stopWords = setOf("the", "a", "an", "of")
        val lastWord = Regex("""(\p{L}+)[\s.]*$""")
        val foreignWord = Regex(
            """(?:第|(?<!\p{L})(?:глава|гл\.?|capítulo|capitulo|cap\.?|chapitre|kapitel|bölüm|chương|bab|rozdział|capitolo|hoofdstuk|episódio|episodio|эпизод)|화|話|话|章)\s*$""",
        )

        // Prologue and epilogue are left out: sources title real chapters with them.
        val extraWord = Regex(
            """\b(?:extra|bonus|omake|special|side ?story|notice|announcement|interlude|afterword|hiatus)\b|番外|外传|외전""",
        )

        // After a chapter word, typos included ("Chaprer 19"), or leading the name.
        fun isChapterPosition(before: String): Boolean =
            ChapterRecognition.chapterPrefix.containsMatchIn(before) ||
                before.none { it.isLetter() } ||
                foreignWord.containsMatchIn(before.trimEnd()) ||
                lastWord.find(before)?.groupValues?.get(1)?.let(::isMarkerTypo) == true

        // "Chapter 16-20" is a span of chapters, which the recognizer reads as 16.2.
        fun isRange(text: String, at: MatchResult, whole: Long): Boolean =
            Regex("""^-([0-9]+)""").find(text.substring(at.range.last + 1))
                ?.groupValues?.get(1)?.toLongOrNull()?.let { it > whole } == true

        fun isMarkerTypo(word: String): Boolean = word.length >= 5 && editDistance(word, "chapter") <= 2

        fun editDistance(a: String, b: String): Int {
            val row = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var diagonal = row[0]
                row[0] = i
                for (j in 1..b.length) {
                    val above = row[j]
                    row[j] = minOf(row[j] + 1, row[j - 1] + 1, diagonal + if (a[i - 1] == b[j - 1]) 0 else 1)
                    diagonal = above
                }
            }
            return row[b.length]
        }
    }
}

/**
 * Each chapter that has copies, with how many its set holds, by [idOf]: the chapters "Skip duplicate
 * chapters" folds into one, of which the reader stops on one. Built from [chapterCopySets] with the
 * arguments both readers pass it, so the details screen shows the same sets the reader skips.
 */
fun <T> List<T>.duplicateCopyCounts(
    numberOf: (T) -> Double,
    nameOf: (T) -> String,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
    idOf: (T) -> Long,
): Map<Long, Int> =
    chapterCopySets(numberOf, nameOf, originOf, ownerOf)
        .filter { it.size > 1 }
        .flatMap { copies -> copies.map { idOf(it) to copies.size } }
        .toMap()
