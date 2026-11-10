package reikai.domain.chapter

import tachiyomi.domain.chapter.service.ChapterRecognition
import kotlin.math.floor

/**
 * Splits [this] into sets of copies of one chapter, every chapter in exactly one set, in the order of
 * each set's first member. Copies share an entry ([ownerOf]) and a recognised number, write that number
 * as the chapter's own, name the same volume when both name one, and come from different scanlators or
 * carry matching titles. A shared number alone is not enough: "Interlude 1" is no copy of "Chapter 1".
 * [markOf] overrides the rule by hand: [CopyMark.NeverSkip] stands alone, and one entry's chapters in one
 * [CopyMark.Group] are copies whatever their numbers. The rule is in docs/dev/subsystems/reader.md.
 */
fun <T> List<T>.chapterCopySets(
    numberOf: (T) -> Double,
    nameOf: (T) -> String,
    originOf: (T) -> String?,
    ownerOf: (T) -> Long,
    markOf: (T) -> CopyMark? = { null },
): List<List<T>> {
    val marks = map(markOf)
    val root = IntArray(size) { it }
    fun find(i: Int): Int = if (root[i] == i) i else find(root[i]).also { root[i] = it }
    fun join(i: Int, j: Int) {
        root[find(j)] = find(i)
    }
    indices.filter { marks[it] != CopyMark.NeverSkip }
        .groupBy { ownerOf(this[it]) to numberOf(this[it]) }.values.forEach { sameNumber ->
            val number = numberOf(this[sameNumber.first()])
            if (sameNumber.size == 1 || !isRecognizedChapterNumber(number)) return@forEach
            val written = sameNumber.map { WrittenChapter(nameOf(this[it]), number, originOf(this[it])) }
            for (a in sameNumber.indices) {
                for (b in a + 1 until sameNumber.size) {
                    if (written[a].isCopyOf(written[b])) join(sameNumber[a], sameNumber[b])
                }
            }
        }
    indices.filter { marks[it] is CopyMark.Group }
        .groupBy { ownerOf(this[it]) to marks[it] }.values
        .forEach { grouped -> grouped.drop(1).forEach { join(grouped.first(), it) } }
    // Grouping by root in index order keeps each set in list order and the sets in first-member order.
    return indices.groupBy(::find).values.map { members -> members.map { this[it] } }
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
        volume = ChapterRecognition.volume.find(lower)?.groupValues?.get(1)?.toDoubleOrNull() ?: leadingVolume(lower)
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
        val rangeEnd = Regex("""^-([0-9]+)""")
        val foreignWord = Regex(
            """(?:第|(?<!\p{L})(?:глава|гл\.?|capítulo|capitulo|cap\.?|chapitre|kapitel|bölüm|chương|bab|rozdział|capitolo|hoofdstuk|episódio|episodio|эпизод)|화|話|话|章)\s*$""",
        )

        // Prologue and epilogue are left out: sources title real chapters with them.
        val extraWord = Regex(
            """\b(?:extra|bonus|omake|special|side ?story|notice|announcement|interlude|afterword|hiatus)\b|番外|外传|외전""",
        )

        // A bare number leading a name whose chapter number comes after it, as a local volume folder "01" leads
        // "01 - Ch 001" or "01 - 001": that number is the volume, so chapters numbered again in each volume stay
        // apart. The local source numbers such a chapter from its own name, never the folder's.
        val leadingNumber = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*[-:._]?\s+""")
        val chapterNumberAfter = Regex("""^[0-9]|\b(?:ch|chap|chapter|ep|episode)\.?\s*#?[0-9]""")

        fun leadingVolume(name: String): Double? = leadingNumber.find(name)
            ?.takeIf { chapterNumberAfter.containsMatchIn(name.substring(it.range.last + 1)) }
            ?.groupValues?.get(1)?.toDoubleOrNull()

        // After a chapter word, typos included ("Chaprer 19"), or leading the name.
        fun isChapterPosition(before: String): Boolean =
            ChapterRecognition.chapterPrefix.containsMatchIn(before) ||
                before.none { it.isLetter() } ||
                foreignWord.containsMatchIn(before.trimEnd()) ||
                lastWord.find(before)?.groupValues?.get(1)?.let(::isMarkerTypo) == true

        // "Chapter 16-20" is a span of chapters, which the recognizer reads as 16.2.
        fun isRange(text: String, at: MatchResult, whole: Long): Boolean =
            rangeEnd.find(text.substring(at.range.last + 1))
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
    markOf: (T) -> CopyMark?,
): Map<Long, Int> =
    chapterCopySets(numberOf, nameOf, originOf, ownerOf, markOf)
        .filter { it.size > 1 }
        .flatMap { copies -> copies.map { idOf(it) to copies.size } }
        .toMap()
