package reikai.presentation.reader

/**
 * Which of the strip's read-through reports become reading progress. The library reports every move, an open
 * or a jump included, and a landing's read-through page is usually the one above where it landed, so nothing
 * is read until the reader moves the strip. The first move then counts what was already read through, unless
 * that is only [report]'s fallback, the page above the top one.
 */
internal class StripReadThrough<T : Any> {

    @Volatile
    private var last: T? = null

    @Volatile
    private var landing = true

    /** An open or a jump: what the next reports read through is where the strip landed, not reading. */
    fun land() {
        landing = true
        last = null
    }

    /** The page to mark read for a report of [readThrough], with [moved] set when a gesture or a step moved it. */
    fun report(readThrough: T?, fallback: T?, moved: Boolean): T? {
        val changed = readThrough !== last
        last = readThrough
        if (landing) {
            if (!moved) return null
            landing = false
            return readThrough?.takeIf { it !== fallback }
        }
        return readThrough.takeIf { changed }
    }
}
