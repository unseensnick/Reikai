package reikai.presentation.reader

/**
 * Whether a chapter reopens where it was left, for both content types. One already read opens at its start,
 * upstream's rule for manga, since its stored position is where it ended and it would reopen on its last
 * screen; the reader's "Resume reading position" setting keeps that position anyway.
 */
object ReaderResume {

    fun keepsPosition(read: Boolean, preserveOnRead: Boolean): Boolean = !read || preserveOnRead
}
