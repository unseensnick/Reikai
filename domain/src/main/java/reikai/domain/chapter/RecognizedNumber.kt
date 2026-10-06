package reikai.domain.chapter

/**
 * Whether [number] is a chapter number the recognizer read, which it stores as zero or above; a
 * negative one is the recognizer finding none. Mihon's `Chapter.isRecognizedNumber` reads through this
 * too, so manga and novels cannot disagree on chapter 0.
 */
fun isRecognizedChapterNumber(number: Double): Boolean = number >= 0.0
