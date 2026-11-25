package reikai.domain.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterRecognition

/**
 * Whether [number] is a chapter number the recognizer read, which it stores as zero or above; a
 * negative one is the recognizer finding none. Mihon's `Chapter.isRecognizedNumber` reads through this
 * too, so manga and novels cannot disagree on chapter 0.
 */
fun isRecognizedChapterNumber(number: Double): Boolean = number >= 0.0

/** What two chapter numbers are compared by, narrowed to Float: a source-reported number is a widened float
 *  and a parsed one a double, so "10.1" from each must still be the same chapter. */
fun chapterNumberKey(number: Double): Float = number.toFloat()

/**
 * [chapters], given newest first, numbered from the whole list by Mihon's recognizer: a name that tells
 * no number takes one from its neighbours. A number the source gave ([numberOf], -1 for none) is kept,
 * and none found is -1. Both syncs number their lists through this, so one list reads alike either way.
 */
fun <T> recognizeChapterNumbers(
    title: String,
    chapters: List<T>,
    nameOf: (T) -> String,
    numberOf: (T) -> Double,
    withNumber: (T, Double) -> T,
): List<T> {
    // The recognizer reads only a chapter's name and number, so a stand-in carries a novel chapter too.
    val numberings = ChapterRecognition.parseChapterNumbers(
        title,
        chapters.map { Chapter.create().copy(name = nameOf(it), chapterNumber = numberOf(it)) },
    )
    return chapters.zip(numberings) { chapter, numbering ->
        withNumber(chapter, numbering.chapter?.let(ChapterRecognition::toDouble) ?: -1.0)
    }
}
