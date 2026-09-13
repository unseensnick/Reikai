package reikai.presentation.reader

/** A paragraph of a chapter, counted as [ReadAloudSurface.paragraphs] counts them. */
data class ReadAloudPosition(val chapterId: Long, val paragraph: Int)

/**
 * What read-aloud needs from a text renderer: the chapter as paragraphs, where the reader is, and a
 * mark on the paragraph being spoken. Both renderers count paragraphs by one rule, so a position saved
 * in one names the same text in the other: a non-blank line of the text as shown, with whitespace runs
 * collapsed to one space, trimmed, object-replacement characters removed and ruby readings left out.
 * `TextViewportContractTest` holds both renderers to it. Every question is answered, with null once the
 * document it was asked of is replaced or destroyed, so a caller waits on none with a timeout.
 */
interface ReadAloudSurface {

    /** The chapter's paragraphs as this renderer shows them, or null when it does not hold the chapter. */
    suspend fun paragraphs(chapterId: Long): List<String>?

    /** The first paragraph at least partly on screen, or null while nothing is rendered. */
    suspend fun firstVisibleParagraph(): ReadAloudPosition?

    /**
     * Marks [position] as the one being spoken, clearing any earlier mark, or only clears for null.
     * With "keep in view" on, a paragraph not fully on screen is scrolled to, which is never a drag: it
     * steps no chapter. The position is kept with highlighting off, so following still works, and it
     * is dropped with its chapter.
     */
    fun highlight(position: ReadAloudPosition?)
}
